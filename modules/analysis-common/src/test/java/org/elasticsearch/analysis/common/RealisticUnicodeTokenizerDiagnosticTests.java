/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic test to understand how the standard tokenizer handles
 * randomRealisticUnicodeOfLengthBetween output vs ASCII.
 * Not for CI — run manually.
 */
public class RealisticUnicodeTokenizerDiagnosticTests extends ESTestCase {

    private static final int SAMPLE_SIZE = 500;

    public void testCompareTokenizerBehavior() throws IOException {
        logger.info("=== Standard Tokenizer Behavior: Realistic Unicode vs ASCII ===\n");

        analyzeCategory("ASCII short (3-6 chars)", SAMPLE_SIZE, () -> randomAlphaOfLengthBetween(3, 6));
        analyzeCategory("ASCII long (10-25 chars)", SAMPLE_SIZE, () -> randomAlphaOfLengthBetween(10, 25));
        analyzeCategory("Realistic Unicode short (3-6 chars)", SAMPLE_SIZE, () -> randomRealisticUnicodeOfLengthBetween(3, 6));
        analyzeCategory("Realistic Unicode long (10-25 chars)", SAMPLE_SIZE, () -> randomRealisticUnicodeOfLengthBetween(10, 25));
    }

    public void testShowSampleTokenization() throws IOException {
        logger.info("=== Sample Tokenizations of Realistic Unicode ===\n");

        int shown = 0;
        for (int i = 0; i < 200 && shown < 20; i++) {
            String input = randomRealisticUnicodeOfLengthBetween(3, 6);
            List<String> tokens = tokenize(input);
            if (tokens.size() != 1 || tokens.getFirst().length() != input.length()) {
                logger.info(
                    "  Input: [{}] (len={}, utf8={} bytes) → {} tokens: {}",
                    escape(input),
                    input.length(),
                    input.getBytes(StandardCharsets.UTF_8).length,
                    tokens.size(),
                    tokens.stream().map(t -> "[" + escape(t) + "]").toList()
                );
                shown++;
            }
        }

        shown = 0;
        logger.info("\n--- Long (10-25) ---");
        for (int i = 0; i < 200 && shown < 20; i++) {
            String input = randomRealisticUnicodeOfLengthBetween(10, 25);
            List<String> tokens = tokenize(input);
            if (tokens.size() != 1 || tokens.getFirst().length() != input.length()) {
                logger.info(
                    "  Input: [{}] (len={}, utf8={} bytes) → {} tokens: {}",
                    escape(input),
                    input.length(),
                    input.getBytes(StandardCharsets.UTF_8).length,
                    tokens.size(),
                    tokens.stream().map(t -> "[" + escape(t) + "]").toList()
                );
                shown++;
            }
        }
    }

    public void testUtf8BytesPerToken() throws IOException {
        logger.info("=== UTF-8 Bytes Per Surviving Token ===\n");

        bytesPerTokenStats("ASCII short (3-6)", SAMPLE_SIZE, () -> randomAlphaOfLengthBetween(3, 6));
        bytesPerTokenStats("ASCII long (10-25)", SAMPLE_SIZE, () -> randomAlphaOfLengthBetween(10, 25));
        bytesPerTokenStats("Unicode short (3-6)", SAMPLE_SIZE, () -> randomRealisticUnicodeOfLengthBetween(3, 6));
        bytesPerTokenStats("Unicode long (10-25)", SAMPLE_SIZE, () -> randomRealisticUnicodeOfLengthBetween(10, 25));
    }

    private void analyzeCategory(String label, int sampleSize, StringSupplier supplier) throws IOException {
        int totalInputChars = 0;
        int totalInputUtf8Bytes = 0;
        int totalOutputTokens = 0;
        int totalOutputChars = 0;
        int totalOutputUtf8Bytes = 0;
        int zeroTokenInputs = 0;
        int multiTokenInputs = 0;

        for (int i = 0; i < sampleSize; i++) {
            String input = supplier.get();
            totalInputChars += input.length();
            totalInputUtf8Bytes += input.getBytes(StandardCharsets.UTF_8).length;

            List<String> tokens = tokenize(input);
            totalOutputTokens += tokens.size();
            if (tokens.isEmpty()) zeroTokenInputs++;
            if (tokens.size() > 1) multiTokenInputs++;

            for (String token : tokens) {
                totalOutputChars += token.length();
                totalOutputUtf8Bytes += token.getBytes(StandardCharsets.UTF_8).length;
            }
        }

        double avgInputChars = (double) totalInputChars / sampleSize;
        double avgInputBytes = (double) totalInputUtf8Bytes / sampleSize;
        double avgOutputTokens = (double) totalOutputTokens / sampleSize;
        double avgOutputChars = totalOutputTokens > 0 ? (double) totalOutputChars / totalOutputTokens : 0;
        double avgOutputBytes = totalOutputTokens > 0 ? (double) totalOutputUtf8Bytes / totalOutputTokens : 0;

        logger.info("--- {} ({} samples) ---", label, sampleSize);
        logger.info("  Avg input chars:          {}", String.format("%.1f", avgInputChars));
        logger.info("  Avg input UTF-8 bytes:    {}", String.format("%.1f", avgInputBytes));
        logger.info("  Avg output tokens/input:  {}", String.format("%.2f", avgOutputTokens));
        logger.info("  Zero-token inputs:        {} ({}%)", zeroTokenInputs, zeroTokenInputs * 100 / sampleSize);
        logger.info("  Multi-token inputs:       {} ({}%)", multiTokenInputs, multiTokenInputs * 100 / sampleSize);
        logger.info("  Avg output token chars:   {}", String.format("%.1f", avgOutputChars));
        logger.info("  Avg output token UTF-8 bytes: {}", String.format("%.1f", avgOutputBytes));
        logger.info("  Total input UTF-8 bytes:  {}", totalInputUtf8Bytes);
        logger.info("  Total output UTF-8 bytes: {}", totalOutputUtf8Bytes);
        logger.info(
            "  Byte expansion ratio (output/input): {}",
            totalInputUtf8Bytes > 0 ? String.format("%.2f", (double) totalOutputUtf8Bytes / totalInputUtf8Bytes) : "N/A"
        );
        logger.info("");
    }

    private void bytesPerTokenStats(String label, int sampleSize, StringSupplier supplier) throws IOException {
        List<Integer> bytesPerToken = new ArrayList<>();
        int uniqueFirstBytes = 0;
        boolean[] seenFirstByte = new boolean[256];

        for (int i = 0; i < sampleSize; i++) {
            String input = supplier.get();
            List<String> tokens = tokenize(input);
            for (String token : tokens) {
                byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
                bytesPerToken.add(bytes.length);
                int firstByte = bytes[0] & 0xFF;
                if (seenFirstByte[firstByte] == false) {
                    seenFirstByte[firstByte] = true;
                    uniqueFirstBytes++;
                }
            }
        }

        bytesPerToken.sort(Integer::compareTo);
        int count = bytesPerToken.size();
        double avg = bytesPerToken.stream().mapToInt(Integer::intValue).average().orElse(0);

        logger.info("--- {} ---", label);
        logger.info("  Total tokens:       {}", count);
        logger.info("  Avg bytes/token:    {}", String.format("%.1f", avg));
        if (count > 0) {
            logger.info("  Median bytes/token: {}", bytesPerToken.get(count / 2));
            logger.info("  Min bytes/token:    {}", bytesPerToken.getFirst());
            logger.info("  Max bytes/token:    {}", bytesPerToken.getLast());
        }
        logger.info("  Unique first UTF-8 bytes: {} (of 256 possible — proxy for FST root branching)", uniqueFirstBytes);
        logger.info("");
    }

    private static List<String> tokenize(String input) throws IOException {
        List<String> tokens = new ArrayList<>();
        try (Tokenizer tokenizer = new StandardTokenizer()) {
            tokenizer.setReader(new StringReader(input));
            CharTermAttribute termAttr = tokenizer.addAttribute(CharTermAttribute.class);
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                tokens.add(termAttr.toString());
            }
            tokenizer.end();
        }
        return tokens;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface StringSupplier {
        String get();
    }
}
