/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.vector;

import org.apache.lucene.misc.store.DirectIODirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.ReadAdvice;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.store.FsDirectoryFactory;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;

@Fork(value = 1, jvmArgsPrepend = { "--enable-native-access=ALL-UNNAMED" })
@Warmup(iterations = 2)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class VectorIOBenchmark {
    static {
        LogConfigurator.configureESLogging(); // native access requires logging to be initialized
    }

    private static final String TEST_DIR = "/home/esbench/.rally/benchmarks/races/test/index";

    private final Random random = new Random();
    private Directory dir;
    private IndexInput input;

    private ExecutorService executor;

    @Param({ "200000000" })
    private int numVectors;

    @Param({ "1024" })
    private int dims;

    @Param({ "100" })
    private int numVectorPerThread;

    @Param({ "32" })
    private int numThreads;

    @Param({ "true", "false" })
    private boolean prefetch;

    @Param({ "NIOFS" })
    private String readMethod;

    @Param({ "true" })
    private boolean align;

    private final byte[] paddingBytes = new byte[3023];

    private String getFileName() {
        return "test-" + (align ? "aligned-" : "unaligned-") + numVectors + ".dat";
    }

    @Setup
    public void setUp() throws IOException {
        Logger log = LogManager.getLogger(VectorIOBenchmark.class);
        switch (readMethod) {
            case "DIRECT_IO":
                var delegate = new MMapDirectory(Path.of(TEST_DIR));
                dir = new FsDirectoryFactory.AlwaysDirectIODirectory(delegate, 8192, DirectIODirectory.DEFAULT_MIN_BYTES_DIRECT, 0);
                break;
            case "MMAP_RANDOM":
                var mmapDir = new MMapDirectory(Path.of(TEST_DIR));
                mmapDir.setReadAdvice((s, c) -> Optional.of(ReadAdvice.RANDOM));
                dir = mmapDir;
                break;
            case "MMAP_NORMAL":
                var mmapDir2 = new MMapDirectory(Path.of(TEST_DIR));
                mmapDir2.setReadAdvice((s, c) -> Optional.of(ReadAdvice.NORMAL));
                dir = mmapDir2;
                break;
            case "NIOFS":
                dir = new PrefetchNIOFSDirectory(Path.of(TEST_DIR));
                break;
            default:
                throw new IllegalArgumentException("Unknown read method [" + readMethod + "]");
        }

        final String fileName = getFileName();
        try {
            this.input = dir.openInput(fileName, IOContext.DEFAULT);
            log.info("Reuse input file [{}]", fileName);
        } catch (NoSuchFileException exc) {
            log.info("Creating input file [{}]", fileName);
            try (var out = dir.createOutput(fileName, IOContext.DEFAULT)) {
                if (align == false) {
                    out.writeBytes(paddingBytes, 0, paddingBytes.length);
                }
                ByteBuffer buf = ByteBuffer.allocate(dims * 4);
                for (int i = 0; i < numVectors; i++) {
                    if (i % 1_000_000 == 0) {
                        log.info("Writing " + i);
                    }
                    createVector(random, buf.asFloatBuffer(), dims);
                    out.writeBytes(buf.array(), 0, buf.limit());
                    buf.rewind();
                }
            }
            this.input = dir.openInput(fileName, IOContext.DEFAULT);
        }
        this.executor = Executors.newFixedThreadPool(numThreads);
    }

    @TearDown
    public void tearDown() throws IOException {
        input.close();
        dir.close();
        executor.shutdown();
    }

    private static void createVector(Random random, FloatBuffer buf, int dims) {
        for (int i = 0; i < dims; i++) {
            buf.put(random.nextFloat());
        }
    }

    private long randomOffset() {
        return (random.nextLong(0, numVectors-1) * dims * 4) + (align == false ? paddingBytes.length : 0);
    }

    @Benchmark
    public int run() throws IOException, ExecutionException, InterruptedException {
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(new ReadThread(input.clone(), numVectorPerThread, new float[dims])));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        return futures.size();
    }

    class ReadThread implements Runnable {
        private final IndexInput in;
        private final int numRead;
        private final float[] vector;
        private int bytesRead;

        ReadThread(IndexInput in, int numRead, float[] vector) {
            this.in = in;
            this.numRead = numRead;
            this.vector = vector;
        }

        @Override
        public void run() {
            long[] offsets = new long[numRead];
            try {
                for (int i = 0; i < numRead; i++) {
                    offsets[i] = randomOffset();
                    if (prefetch) {
                        in.prefetch(offsets[i], vector.length * 4L);
                    }
                }
                for (long offset : offsets) {
                    in.seek(offset);
                    in.readFloats(vector, 0, vector.length);
                    bytesRead += vector.length;
                }
            } catch(IOException e) {
                throw new RuntimeException(e);
            }
        }

        public int getBytesRead() {
            return bytesRead;
        }
    }
}
