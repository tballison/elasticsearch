/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.lucene.util.IntsRefBuilder;
import org.apache.lucene.util.OfflineSorter;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.FSTCompiler;
import org.apache.lucene.util.fst.Util;
import org.elasticsearch.synonyms.SynonymRule;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Builds a {@link SynonymMap} from {@link SynonymRule}[] using an external
 * (on-disk) sort via {@link OfflineSorter}, so peak heap is bounded by the
 * sort buffer (default ~16 MB) rather than the full synonym set.
 *
 * <p>Each analyzed {@code (input, output, includeOrig)} triple is written to a
 * temp file as it is produced.  After all rules are parsed, {@link OfflineSorter}
 * sorts the temp file by input, and {@link #build()} streams the sorted records
 * directly into {@link FSTCompiler}, grouping consecutive same-input records
 * on the fly.
 *
 * <p><b>Record encoding</b> (as passed to {@link OfflineSorter.ByteSequencesWriter}):
 * {@code [2-byte big-endian input-UTF8-length N][N bytes input UTF-8]
 * [M bytes output UTF-8][1 byte includeOrig flag]}.
 * {@code WORD_SEPARATOR} (\u0000) is preserved as the single byte {@code 0x00}
 * in standard UTF-8 encoding.  The comparator sorts by the input bytes only.
 *
 * <p>Callers must ensure {@link #close()} is called (prefer try-with-resources)
 * so the temp directory is cleaned up even if {@link #parse} throws.
 */
public class SortedSynonymMapBuilder implements Closeable {

    static final char WORD_SEPARATOR = SynonymMap.WORD_SEPARATOR;

    /** Comparator used by {@link OfflineSorter}: sort by the input bytes only. */
    static final Comparator<BytesRef> BY_INPUT_COMPARATOR = (a, b) -> {
        int aLen = ((a.bytes[a.offset] & 0xFF) << 8) | (a.bytes[a.offset + 1] & 0xFF);
        int bLen = ((b.bytes[b.offset] & 0xFF) << 8) | (b.bytes[b.offset + 1] & 0xFF);
        return Arrays.compareUnsigned(
            a.bytes, a.offset + 2, a.offset + 2 + aLen,
            b.bytes, b.offset + 2, b.offset + 2 + bLen
        );
    };

    private static final String INPUT_FILE = "synonyms.bin";

    private final Path tempDir;
    private final FSDirectory directory;
    private final IndexOutput rawOutput;
    private final OfflineSorter.ByteSequencesWriter writer;

    private final CharsRefBuilder charsBuilder = new CharsRefBuilder();
    private final BytesRefBuilder utf8Input = new BytesRefBuilder();
    private final BytesRefBuilder utf8Output = new BytesRefBuilder();

    int maxHorizontalContext = 1;
    private int pairCount = 0;
    private boolean writerClosed = false;
    private boolean cleaned = false;

    public SortedSynonymMapBuilder() throws IOException {
        tempDir = Files.createTempDirectory("es-synonyms-sort");
        directory = FSDirectory.open(tempDir);
        rawOutput = directory.createOutput(INPUT_FILE, IOContext.DEFAULT);
        writer = new OfflineSorter.ByteSequencesWriter(rawOutput);
    }

    /**
     * Parses synonym rules in Solr format, analyzes each term, and writes
     * raw pairs to the temp file. Call {@link #build()} when done.
     */
    public void parse(SynonymRule[] rules, Analyzer analyzer, boolean expand, boolean lenient) throws IOException {
        for (SynonymRule rule : rules) {
            String synonyms = rule.synonyms();
            if (synonyms == null || synonyms.isBlank()) {
                continue;
            }
            parseRule(synonyms.trim(), analyzer, expand, lenient);
        }
    }

    private void parseRule(String rule, Analyzer analyzer, boolean expand, boolean lenient) throws IOException {
        String[] sides = rule.split("=>");
        if (sides.length == 2) {
            String[] inputs = sides[0].split(",");
            String[] outputs = sides[1].split(",");
            for (String input : inputs) {
                CharsRef analyzedInput = analyzeToCharsRef(input.trim(), analyzer, lenient);
                if (analyzedInput == null) continue;
                for (String output : outputs) {
                    CharsRef analyzedOutput = analyzeToCharsRef(output.trim(), analyzer, lenient);
                    if (analyzedOutput == null) continue;
                    writePair(analyzedInput, analyzedOutput, false);
                }
            }
        } else {
            String[] terms = rule.split(",");
            CharsRef[] analyzed = new CharsRef[terms.length];
            for (int i = 0; i < terms.length; i++) {
                analyzed[i] = analyzeToCharsRef(terms[i].trim(), analyzer, lenient);
            }
            if (expand) {
                for (int i = 0; i < analyzed.length; i++) {
                    if (analyzed[i] == null) continue;
                    for (int j = 0; j < analyzed.length; j++) {
                        if (i == j || analyzed[j] == null) continue;
                        writePair(analyzed[i], analyzed[j], true);
                    }
                }
            } else {
                if (analyzed[0] == null) return;
                for (int i = 1; i < analyzed.length; i++) {
                    if (analyzed[i] == null) continue;
                    writePair(analyzed[i], analyzed[0], false);
                }
            }
        }
    }

    private CharsRef analyzeToCharsRef(String text, Analyzer analyzer, boolean lenient) throws IOException {
        try (TokenStream ts = analyzer.tokenStream("", text)) {
            CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);
            PositionIncrementAttribute posIncAtt = ts.addAttribute(PositionIncrementAttribute.class);
            ts.reset();
            charsBuilder.clear();
            boolean first = true;
            while (ts.incrementToken()) {
                if (posIncAtt.getPositionIncrement() != 1 && first == false) {
                    continue;
                }
                if (first == false) {
                    charsBuilder.append(WORD_SEPARATOR);
                }
                charsBuilder.append(termAtt.buffer(), 0, termAtt.length());
                first = false;
            }
            ts.end();
        }
        if (charsBuilder.length() == 0) {
            if (lenient) return null;
            throw new IllegalArgumentException("synonym term '" + text + "' analyzed to nothing");
        }
        return charsBuilder.toCharsRef();
    }

    private void writePair(CharsRef input, CharsRef output, boolean includeOrig) throws IOException {
        utf8Input.copyChars(input.chars, input.offset, input.length);
        utf8Output.copyChars(output.chars, output.offset, output.length);

        int inputLen = utf8Input.length();
        int outputLen = utf8Output.length();
        int totalLen = 2 + inputLen + outputLen + 1;

        byte[] buf = new byte[totalLen];
        buf[0] = (byte) (inputLen >> 8);
        buf[1] = (byte) (inputLen & 0xFF);
        System.arraycopy(utf8Input.bytes(), 0, buf, 2, inputLen);
        System.arraycopy(utf8Output.bytes(), 0, buf, 2 + inputLen, outputLen);
        buf[totalLen - 1] = includeOrig ? (byte) 1 : (byte) 0;

        writer.write(buf, 0, totalLen);
        pairCount++;

        maxHorizontalContext = Math.max(maxHorizontalContext, countWords(input));
        maxHorizontalContext = Math.max(maxHorizontalContext, countWords(output));
    }

    static int countWords(CharsRef ref) {
        int count = 1;
        for (int i = ref.offset; i < ref.offset + ref.length; i++) {
            if (ref.chars[i] == WORD_SEPARATOR) count++;
        }
        return count;
    }

    /**
     * Flushes the write buffer, sorts the temp file on disk, streams the sorted
     * records into {@link FSTCompiler}, then cleans up temp files.
     */
    public SynonymMap build() throws IOException {
        closeWriter();
        if (pairCount == 0) {
            return new SynonymMap.Builder().build();
        }
        try {
            OfflineSorter sorter = new OfflineSorter(directory, "sort", BY_INPUT_COMPARATOR);
            String sortedFile = sorter.sort(INPUT_FILE);
            try (ChecksumIndexInput in = directory.openChecksumInput(sortedFile);
                 OfflineSorter.ByteSequencesReader reader = new OfflineSorter.ByteSequencesReader(in, sortedFile)) {
                return buildFromSortedReader(reader, maxHorizontalContext);
            }
        } finally {
            cleanup();
        }
    }

    @Override
    public void close() throws IOException {
        cleanup();
    }

    private void closeWriter() throws IOException {
        if (writerClosed == false) {
            writerClosed = true;
            // OfflineSorter.sort() validates a CodecUtil footer on the input file.
            // ByteSequencesWriter.close() only calls out.close() — no footer written.
            // We must write the footer to the raw IndexOutput before closing.
            CodecUtil.writeFooter(rawOutput);
            writer.close(); // just calls rawOutput.close()
        }
    }

    private void cleanup() throws IOException {
        if (cleaned) return;
        cleaned = true;
        try {
            closeWriter();
        } catch (IOException ignored) {}
        try {
            directory.close();
        } catch (IOException ignored) {}
        deleteDirectory(tempDir);
    }

    private static void deleteDirectory(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /**
     * Streams pre-sorted records into {@link FSTCompiler}, grouping consecutive
     * same-input records and deduplicating output ords on the fly.
     * Package-private for testing.
     */
    static SynonymMap buildFromSortedReader(OfflineSorter.ByteSequencesReader reader, int maxHorizontalContext)
        throws IOException {

        BytesRefHash words = new BytesRefHash();
        BytesRefBuilder utf8Scratch = new BytesRefBuilder();
        ByteSequenceOutputs fstOutputs = ByteSequenceOutputs.getSingleton();
        FSTCompiler<BytesRef> fstCompiler = new FSTCompiler.Builder<>(FST.INPUT_TYPE.BYTE4, fstOutputs).build();
        IntsRefBuilder scratchInts = new IntsRefBuilder();
        byte[] buf = new byte[0];

        BytesRef record = reader.next();
        while (record != null) {
            // Save the input bytes from the first record of this group
            int inputLen = ((record.bytes[record.offset] & 0xFF) << 8) | (record.bytes[record.offset + 1] & 0xFF);
            byte[] inputBytes = Arrays.copyOfRange(record.bytes, record.offset + 2, record.offset + 2 + inputLen);

            int[] seen = new int[8];
            int seenCount = 0;
            boolean includeOrig = false;

            // Consume all consecutive records with the same input
            while (record != null) {
                int recInputLen = ((record.bytes[record.offset] & 0xFF) << 8)
                    | (record.bytes[record.offset + 1] & 0xFF);
                if (recInputLen != inputLen
                    || Arrays.compareUnsigned(
                        record.bytes, record.offset + 2, record.offset + 2 + recInputLen,
                        inputBytes, 0, inputLen
                    ) != 0) {
                    break; // different input — start a new group
                }

                int outputStart = record.offset + 2 + recInputLen;
                int outputLen = record.length - 2 - recInputLen - 1;
                utf8Scratch.copyBytes(record.bytes, outputStart, outputLen);
                int ord = words.add(utf8Scratch.get());
                if (ord < 0) ord = (-ord) - 1;

                includeOrig |= (record.bytes[record.offset + record.length - 1] == 1);

                boolean dup = false;
                for (int k = 0; k < seenCount; k++) {
                    if (seen[k] == ord) { dup = true; break; }
                }
                if (dup == false) {
                    if (seenCount == seen.length) seen = Arrays.copyOf(seen, seen.length * 2);
                    seen[seenCount++] = ord;
                }

                record = reader.next();
            }

            // Encode FST output: [count+flag vint][ord1 vint]...[ordN vint]
            int bufSize = (seenCount + 1) * 5;
            if (buf.length < bufSize) buf = new byte[bufSize];
            ByteArrayDataOutput out = new ByteArrayDataOutput(buf);
            for (int k = 0; k < seenCount; k++) out.writeVInt(seen[k]);
            int ordsLen = out.getPosition();
            out.writeVInt((seenCount << 1) | (includeOrig ? 0 : 1));
            int countLen = out.getPosition() - ordsLen;

            byte[] countBytes = new byte[countLen];
            System.arraycopy(buf, ordsLen, countBytes, 0, countLen);
            System.arraycopy(buf, 0, buf, countLen, ordsLen);
            System.arraycopy(countBytes, 0, buf, 0, countLen);

            // Decode input UTF-8 → String → UTF-32 code points for the FST key
            String inputStr = new String(inputBytes, StandardCharsets.UTF_8);
            Util.toUTF32(inputStr, scratchInts);
            fstCompiler.add(scratchInts.get(), new BytesRef(buf, 0, countLen + ordsLen));
        }

        FST<BytesRef> fst = FST.fromFSTReader(fstCompiler.compile(), fstCompiler.getFSTReader());
        return new SynonymMap(fst, words, maxHorizontalContext);
    }
}
