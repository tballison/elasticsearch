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
import org.elasticsearch.script.field.vectors.BFloat16RankVectorsDocValuesField;
import org.elasticsearch.script.field.vectors.FloatRankVectors;
import org.elasticsearch.simdvec.ESVectorUtil;
import org.elasticsearch.simdvec.MultiBFloat16VectorsSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Element-type aware access to stored {@code rank_vectors} bytes for indexed MaxSim.
 * <p>
 * Queries are always float. Documents may be stored as {@code float}, {@code bfloat16},
 * {@code byte} or {@code bit}; scores are float-query by stored-document inner products, so any
 * global scale applied when quantizing documents scales every score equally and preserves order.
 * {@code bit} documents score {@code q . b} with {@code b} in {0, 1}; relative to a {+1, -1} sign
 * code this differs only by a per-query-vector constant, which also preserves MaxSim order.
 */
final class RankVectorsElementScoring {

    private RankVectorsElementScoring() {}

    /** Number of stored bytes for one vector of {@code dims} logical dimensions. */
    static int vectorBytes(ElementType elementType, int dims) {
        return switch (elementType) {
            case FLOAT -> Math.multiplyExact(dims, Float.BYTES);
            case BFLOAT16 -> Math.multiplyExact(dims, BFloat16.BYTES);
            case BYTE -> dims;
            case BIT -> dims / Byte.SIZE;
        };
    }

    static int vectorCount(ElementType elementType, BytesRef documentVectors, int dims) {
        int vectorBytes = vectorBytes(elementType, dims);
        if (documentVectors.length == 0 || documentVectors.length % vectorBytes != 0) {
            throw new IllegalArgumentException("invalid rank_vectors binary doc value length [" + documentVectors.length + "]");
        }
        return documentVectors.length / vectorBytes;
    }

    /** Exact MaxSim of float query vectors against stored document vectors of the given element type. */
    static float maxSim(ElementType elementType, float[][] queryVectors, BytesRef documentVectors, int dims) {
        int count = vectorCount(elementType, documentVectors, dims);
        return switch (elementType) {
            case FLOAT -> FloatRankVectors.maxSimDotProduct(documentVectors, count, dims, queryVectors);
            case BFLOAT16 -> ESVectorUtil.maxSimDotProduct(bfloat16Source(documentVectors, count, dims), queryVectors, new float[count]);
            case BYTE -> byteMaxSim(queryVectors, documentVectors, count, dims);
            case BIT -> bitMaxSim(queryVectors, documentVectors, count, dims);
        };
    }

    /**
     * Decodes stored document vectors for FDE encoding, so the FDE is derived from the same
     * representation that MaxSim scores rather than from pre-quantization input values.
     * {@code float} tokens are returned unchanged so the encoder still rejects non-unit input; quantized
     * tokens cannot be exactly unit-magnitude and are normalized after decoding.
     */
    static float[][] decodeUnitTokens(ElementType elementType, BytesRef documentVectors, int dims) {
        int count = vectorCount(elementType, documentVectors, dims);
        int vectorBytes = vectorBytes(elementType, dims);
        ByteBuffer buffer = ByteBuffer.wrap(documentVectors.bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[][] tokens = new float[count][dims];
        for (int v = 0; v < count; v++) {
            int offset = documentVectors.offset + v * vectorBytes;
            float[] token = tokens[v];
            switch (elementType) {
                case FLOAT -> {
                    for (int d = 0; d < dims; d++) {
                        token[d] = buffer.getFloat(offset + d * Float.BYTES);
                    }
                }
                case BFLOAT16 -> BFloat16.bFloat16ToFloat(documentVectors.bytes, offset, token, 0, dims, ByteOrder.LITTLE_ENDIAN);
                case BYTE -> {
                    for (int d = 0; d < dims; d++) {
                        token[d] = documentVectors.bytes[offset + d];
                    }
                }
                case BIT -> {
                    for (int d = 0; d < dims; d++) {
                        int bit = (documentVectors.bytes[offset + d / Byte.SIZE] >> (Byte.SIZE - 1 - d % Byte.SIZE)) & 1;
                        token[d] = bit == 1 ? 1f : -1f;
                    }
                }
            }
            if (elementType != ElementType.FLOAT) {
                normalize(token, v);
            }
        }
        return tokens;
    }

    private static void normalize(float[] token, int index) {
        double squared = 0;
        for (float value : token) {
            squared += (double) value * value;
        }
        if (squared == 0) {
            throw new IllegalArgumentException("token vector [" + index + "] has zero magnitude after decoding");
        }
        float inverse = (float) (1d / Math.sqrt(squared));
        for (int d = 0; d < token.length; d++) {
            token[d] *= inverse;
        }
    }

    private static float byteMaxSim(float[][] queryVectors, BytesRef documentVectors, int count, int dims) {
        float[] maxes = new float[queryVectors.length];
        Arrays.fill(maxes, Float.NEGATIVE_INFINITY);
        byte[] scratch = new byte[dims];
        for (int v = 0; v < count; v++) {
            System.arraycopy(documentVectors.bytes, documentVectors.offset + v * dims, scratch, 0, dims);
            for (int q = 0; q < queryVectors.length; q++) {
                maxes[q] = Math.max(maxes[q], ESVectorUtil.ipFloatByte(queryVectors[q], scratch));
            }
        }
        return ESVectorUtil.sum(maxes, maxes.length);
    }

    private static float bitMaxSim(float[][] queryVectors, BytesRef documentVectors, int count, int dims) {
        float[] maxes = new float[queryVectors.length];
        Arrays.fill(maxes, Float.NEGATIVE_INFINITY);
        int vectorBytes = dims / Byte.SIZE;
        for (int v = 0; v < count; v++) {
            int offset = documentVectors.offset + v * vectorBytes;
            for (int q = 0; q < queryVectors.length; q++) {
                maxes[q] = Math.max(maxes[q], ESVectorUtil.ipFloatBit(queryVectors[q], 0, documentVectors.bytes, offset, dims));
            }
        }
        return ESVectorUtil.sum(maxes, maxes.length);
    }

    private static MultiBFloat16VectorsSource bfloat16Source(BytesRef vectorBytes, int numVectors, int dims) {
        return new MultiBFloat16VectorsSource() {
            @Override
            public BytesRef vectorBytes() {
                return vectorBytes;
            }

            @Override
            public int vectorCount() {
                return numVectors;
            }

            @Override
            public int vectorDims() {
                return dims;
            }

            @Override
            public int vectorByteSize() {
                return dims * BFloat16.BYTES;
            }

            @Override
            public Iterator<float[]> vectorValues() {
                return new BFloat16RankVectorsDocValuesField.BFloat16VectorIterator(vectorBytes, new float[dims], numVectors);
            }
        };
    }
}
