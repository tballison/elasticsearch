/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.vector.scorer;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.benchmark.vector.VectorizationInfo;
import org.elasticsearch.script.field.vectors.FloatRankVectors;
import org.elasticsearch.script.field.vectors.VectorIterator;
import org.elasticsearch.simdvec.ESVectorUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Compares the current exact float MaxSim implementation with a matrix-multiply formulation.
 *
 * <p>The matrix score is accepted when it differs from the current reusable path by at most
 * {@code max(1e-3, abs(reference) * 2e-6)}. Matrix multiplication changes the floating-point
 * accumulation order, so bit-for-bit equality is not expected.
 *
 * <p>Run with {@code -prof gc} to compare per-candidate allocation.
 */
@Fork(value = 1, jvmArgsPrepend = { "--add-modules=jdk.incubator.vector" })
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class RankVectorsMaxSimDotProductBenchmark {
    static {
        BenchmarkLogging.configure();
        VectorizationInfo.printOnce();
    }

    @Param({ "128" })
    public int dims;

    @Param({ "200", "500", "1000", "1024" })
    public int numDocVectors;

    @Param({ "16", "32", "64" })
    public int numQueryVectors;

    private float[][] floatDocVectors;
    private float[][] floatQueryVectors;
    private BytesRef encodedDocVectors;
    private FloatRankVectors floatRankVectors;
    private float[] docMatrix;
    private float[] decodedDocMatrix;
    private float[] transposedQueryMatrix;
    private float[] matrixProduct;

    @Setup(Level.Trial)
    public void setup() {
        Random random = new Random(0x5EEDL);
        floatDocVectors = new float[numDocVectors][dims];
        floatQueryVectors = new float[numQueryVectors][dims];
        for (int i = 0; i < numDocVectors; i++) {
            for (int d = 0; d < dims; d++) {
                floatDocVectors[i][d] = random.nextFloat() * 2f - 1f;
            }
        }
        for (int i = 0; i < numQueryVectors; i++) {
            for (int d = 0; d < dims; d++) {
                floatQueryVectors[i][d] = random.nextFloat() * 2f - 1f;
            }
        }

        encodedDocVectors = encodeFloatDocVectors(floatDocVectors);
        BytesRef magnitudes = new BytesRef(new byte[numDocVectors * Float.BYTES]);
        floatRankVectors = new FloatRankVectors(VectorIterator.from(floatDocVectors), magnitudes, numDocVectors, dims, encodedDocVectors);

        docMatrix = new float[numDocVectors * dims];
        decodedDocMatrix = new float[docMatrix.length];
        transposedQueryMatrix = new float[dims * numQueryVectors];
        matrixProduct = new float[numDocVectors * numQueryVectors];
        for (int i = 0; i < numDocVectors; i++) {
            System.arraycopy(floatDocVectors[i], 0, docMatrix, i * dims, dims);
        }
        for (int q = 0; q < numQueryVectors; q++) {
            for (int d = 0; d < dims; d++) {
                transposedQueryMatrix[d * numQueryVectors + q] = floatQueryVectors[q][d];
            }
        }

        float reference = reusableNativeBulkPath();
        assertClose("static Muvera path", reference, staticMuveraPath());
        assertClose("reusable matrix path", reference, reusableMatrixPath());
        assertClose("decode and matrix path", reference, decodeAndMatrixPath());
    }

    @Benchmark
    public float reusableNativeBulkPath() {
        return floatRankVectors.maxSimDotProduct(floatQueryVectors);
    }

    @Benchmark
    public float staticMuveraPath() {
        return FloatRankVectors.maxSimDotProduct(encodedDocVectors, numDocVectors, dims, floatQueryVectors);
    }

    @Benchmark
    public float reusableMatrixPath() {
        return matrixMaxSim(docMatrix);
    }

    @Benchmark
    public float decodeAndMatrixPath() {
        decodeLittleEndianFloats(encodedDocVectors, decodedDocMatrix);
        return matrixMaxSim(decodedDocMatrix);
    }

    private float matrixMaxSim(float[] documents) {
        ESVectorUtil.matrixMultiply(documents, transposedQueryMatrix, numDocVectors, dims, numQueryVectors, matrixProduct, null);
        float sum = 0f;
        for (int q = 0; q < numQueryVectors; q++) {
            float max = Float.NEGATIVE_INFINITY;
            for (int doc = 0; doc < numDocVectors; doc++) {
                max = Math.max(max, matrixProduct[doc * numQueryVectors + q]);
            }
            sum += max;
        }
        return sum;
    }

    private static void decodeLittleEndianFloats(BytesRef encoded, float[] decoded) {
        byte[] bytes = encoded.bytes;
        int offset = encoded.offset;
        for (int i = 0; i < decoded.length; i++) {
            int bits = Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8 | Byte.toUnsignedInt(
                bytes[offset + 2]
            ) << 16 | bytes[offset + 3] << 24;
            decoded[i] = Float.intBitsToFloat(bits);
            offset += Float.BYTES;
        }
    }

    private static void assertClose(String path, float expected, float actual) {
        float tolerance = Math.max(1e-3f, Math.abs(expected) * 2e-6f);
        if (Float.isFinite(actual) == false || Math.abs(expected - actual) > tolerance) {
            throw new IllegalStateException(
                path + " score [" + actual + "] differs from reference [" + expected + "] by more than [" + tolerance + "]"
            );
        }
    }

    private static BytesRef encodeFloatDocVectors(float[][] vectors) {
        int dims = vectors[0].length;
        ByteBuffer buffer = ByteBuffer.allocate(vectors.length * dims * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var floatBuffer = buffer.asFloatBuffer();
        for (float[] vector : vectors) {
            floatBuffer.put(vector);
        }
        return new BytesRef(buffer.array());
    }

}
