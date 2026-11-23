/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.rank.vectors.mapper;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.codec.vectors.BFloat16;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper.ElementType;
import org.elasticsearch.test.ESTestCase;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.hamcrest.Matchers.closeTo;

public class RankVectorsElementScoringTests extends ESTestCase {

    private static final int DIMS = 128;

    public void testMaxSimMatchesDecodedReferenceForEveryElementType() {
        float[][] query = randomQuery();
        int count = randomIntBetween(1, 12);
        for (ElementType elementType : ElementType.values()) {
            BytesRef stored = randomStored(elementType, count);
            float[][] decoded = decodeRaw(elementType, stored, count);
            float expected = referenceMaxSim(query, decoded);
            float actual = RankVectorsElementScoring.maxSim(elementType, query, stored, DIMS);
            assertThat(elementType.toString(), (double) actual, closeTo(expected, 1e-3 * Math.max(1, Math.abs(expected))));
        }
    }

    public void testFloatTokensAreDecodedUnchanged() {
        int count = randomIntBetween(1, 12);
        BytesRef stored = randomStored(ElementType.FLOAT, count);
        float[][] decoded = RankVectorsElementScoring.decodeUnitTokens(ElementType.FLOAT, stored, DIMS);
        float[][] raw = decodeRaw(ElementType.FLOAT, stored, count);
        for (int v = 0; v < count; v++) {
            assertArrayEquals(raw[v], decoded[v], 0f);
        }
    }

    public void testQuantizedTokensAreUnitAndPreserveDirection() {
        int count = randomIntBetween(1, 12);
        for (ElementType elementType : new ElementType[] { ElementType.BFLOAT16, ElementType.BYTE, ElementType.BIT }) {
            BytesRef stored = randomStored(elementType, count);
            float[][] raw = decodeRaw(elementType, stored, count);
            float[][] unit = RankVectorsElementScoring.decodeUnitTokens(elementType, stored, DIMS);
            assertEquals(count, unit.length);
            for (int v = 0; v < count; v++) {
                double squared = 0;
                double cosine = 0;
                double rawSquared = 0;
                for (int d = 0; d < DIMS; d++) {
                    // bit tokens feed the FDE as a {+1, -1} sign code rather than as the {0, 1} values scored by MaxSim
                    double expected = elementType == ElementType.BIT ? 2 * raw[v][d] - 1 : raw[v][d];
                    squared += unit[v][d] * unit[v][d];
                    cosine += unit[v][d] * expected;
                    rawSquared += expected * expected;
                }
                assertThat(elementType.toString(), squared, closeTo(1d, 1e-5));
                assertThat(elementType.toString(), cosine / Math.sqrt(rawSquared), closeTo(1d, 1e-5));
            }
        }
    }

    public void testBitDecodingUsesIpFloatBitOrder() {
        byte[] bits = new byte[DIMS / Byte.SIZE];
        bits[0] = (byte) 0b1000_0000;
        float[][] query = new float[1][DIMS];
        query[0][0] = 1f;
        query[0][1] = 100f;
        assertEquals(1f, RankVectorsElementScoring.maxSim(ElementType.BIT, query, new BytesRef(bits), DIMS), 0f);
        float[][] decoded = RankVectorsElementScoring.decodeUnitTokens(ElementType.BIT, new BytesRef(bits), DIMS);
        assertTrue(decoded[0][0] > 0);
        assertTrue(decoded[0][1] < 0);
    }

    public void testRejectsTruncatedDocValues() {
        expectThrows(
            IllegalArgumentException.class,
            () -> RankVectorsElementScoring.maxSim(ElementType.BYTE, randomQuery(), new BytesRef(new byte[DIMS + 1]), DIMS)
        );
    }

    private static float[][] randomQuery() {
        float[][] query = new float[randomIntBetween(1, 8)][DIMS];
        for (float[] vector : query) {
            for (int d = 0; d < DIMS; d++) {
                vector[d] = randomFloatBetween(-1f, 1f, true);
            }
        }
        return query;
    }

    private static BytesRef randomStored(ElementType elementType, int count) {
        int vectorBytes = RankVectorsElementScoring.vectorBytes(elementType, DIMS);
        ByteBuffer buffer = ByteBuffer.allocate(vectorBytes * count).order(ByteOrder.LITTLE_ENDIAN);
        for (int v = 0; v < count; v++) {
            switch (elementType) {
                case FLOAT -> {
                    for (int d = 0; d < DIMS; d++) {
                        buffer.putFloat(randomFloatBetween(-1f, 1f, true));
                    }
                }
                case BFLOAT16 -> {
                    for (int d = 0; d < DIMS; d++) {
                        buffer.putShort(BFloat16.floatToBFloat16(randomFloatBetween(-1f, 1f, true)));
                    }
                }
                case BYTE -> {
                    for (int d = 0; d < DIMS; d++) {
                        buffer.put(randomValueOtherThan((byte) 0, ESTestCase::randomByte));
                    }
                }
                case BIT -> {
                    for (int b = 0; b < vectorBytes; b++) {
                        buffer.put(randomByte());
                    }
                }
            }
        }
        return new BytesRef(buffer.array());
    }

    /** Values as scored: floats, decoded bfloat16, signed bytes, and {0, 1} bits in big-endian order. */
    private static float[][] decodeRaw(ElementType elementType, BytesRef stored, int count) {
        ByteBuffer buffer = ByteBuffer.wrap(stored.bytes, stored.offset, stored.length).order(ByteOrder.LITTLE_ENDIAN);
        float[][] vectors = new float[count][DIMS];
        for (int v = 0; v < count; v++) {
            switch (elementType) {
                case FLOAT -> {
                    for (int d = 0; d < DIMS; d++) {
                        vectors[v][d] = buffer.getFloat();
                    }
                }
                case BFLOAT16 -> {
                    for (int d = 0; d < DIMS; d++) {
                        vectors[v][d] = BFloat16.bFloat16ToFloat(buffer.getShort());
                    }
                }
                case BYTE -> {
                    for (int d = 0; d < DIMS; d++) {
                        vectors[v][d] = buffer.get();
                    }
                }
                case BIT -> {
                    byte[] bits = new byte[DIMS / Byte.SIZE];
                    buffer.get(bits);
                    for (int d = 0; d < DIMS; d++) {
                        int bit = (bits[d / Byte.SIZE] >> (Byte.SIZE - 1 - d % Byte.SIZE)) & 1;
                        vectors[v][d] = bit;
                    }
                }
            }
        }
        return vectors;
    }

    private static float referenceMaxSim(float[][] query, float[][] documents) {
        double sum = 0;
        for (float[] q : query) {
            double max = Double.NEGATIVE_INFINITY;
            for (float[] document : documents) {
                double dot = 0;
                for (int d = 0; d < DIMS; d++) {
                    dot += q[d] * document[d];
                }
                max = Math.max(max, dot);
            }
            sum += max;
        }
        return (float) sum;
    }
}
