/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.search.ccs;

import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.broadcast.BroadcastResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.inference.SimilarityMeasure;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xpack.inference.mock.TestDenseInferenceServiceExtension;
import org.elasticsearch.xpack.inference.mock.TestSparseInferenceServiceExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertResponse;
import static org.elasticsearch.xpack.inference.integration.IntegrationTestUtils.createInferenceEndpoint;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that query-time inference is executed once per cluster (per unique inference endpoint), never per shard.
 * Both indices deliberately use many more shards than there are inference calls expected, so a per-shard inference
 * regression would show up as an order-of-magnitude jump in the recorded call count. The count is asserted for both
 * sparse and dense (text embedding) endpoints, with {@code search.batched_query_phase} both enabled and disabled
 * because the two settings route per-shard query requests completely differently, and with
 * {@code ccs_minimize_roundtrips} false (the configuration where the local coordinator performs the remote inference
 * metadata roundtrip, see gh#146908) and true.
 */
public class InferenceCallCountCrossClusterSearchIT extends AbstractSemanticCrossClusterSearchTestCase {

    private static final String SPARSE_INFERENCE_ID = "sparse-inference-id";
    private static final String DENSE_INFERENCE_ID = "dense-inference-id";
    private static final String SPARSE_SEMANTIC_FIELD = "sparse-semantic-field";
    private static final String DENSE_SEMANTIC_FIELD = "dense-semantic-field";
    private static final int SHARD_COUNT = 10;
    private static final int DENSE_DIMENSIONS = 32;

    /**
     * Which mock inference service backs the queried field, exposing its own recorded calls and those of the other
     * service. Whatever the queried field type, the other service must never be invoked at query time.
     */
    private enum FieldType {
        SPARSE(SPARSE_SEMANTIC_FIELD, TestSparseInferenceServiceExtension.RECORDED_INFER_CALLS),
        DENSE(DENSE_SEMANTIC_FIELD, TestDenseInferenceServiceExtension.RECORDED_INFER_CALLS);

        private final String fieldName;
        private final List<List<String>> recordedCalls;

        FieldType(String fieldName, List<List<String>> recordedCalls) {
            this.fieldName = fieldName;
            this.recordedCalls = recordedCalls;
        }

        private List<List<String>> otherServiceRecordedCalls() {
            return (this == SPARSE ? DENSE : SPARSE).recordedCalls;
        }
    }

    public void testInferenceCallCountCcsMinimizeRoundTripsFalse() throws Exception {
        setupMultiShardClusters();

        for (boolean batchedQueryPhase : new boolean[] { true, false }) {
            setBatchedQueryPhase(batchedQueryPhase);
            try {
                for (FieldType fieldType : FieldType.values()) {
                    // One inference call on the local coordinator plus one on the remote cluster's handling node,
                    // regardless of shard count or query-phase batching
                    assertInferenceCallCount(fieldType, QUERY_INDICES, false, 2);
                }
            } finally {
                clearBatchedQueryPhase();
            }
        }
    }

    public void testInferenceCallCountCcsMinimizeRoundTripsTrue() throws Exception {
        setupMultiShardClusters();

        for (boolean batchedQueryPhase : new boolean[] { true, false }) {
            setBatchedQueryPhase(batchedQueryPhase);
            try {
                for (FieldType fieldType : FieldType.values()) {
                    assertInferenceCallCount(fieldType, QUERY_INDICES, true, 2);
                }
            } finally {
                clearBatchedQueryPhase();
            }
        }
    }

    public void testInferenceCallCountLocalOnly() throws Exception {
        setupMultiShardClusters();
        for (FieldType fieldType : FieldType.values()) {
            // No remote cluster involved: a single call on the local coordinator covers all shards
            assertInferenceCallCount(fieldType, List.of(LOCAL_INDEX_NAME), randomBoolean(), 1);
        }
    }

    private void assertInferenceCallCount(FieldType fieldType, List<String> indices, boolean ccsMinimizeRoundTrips, int expectedCallCount)
        throws Exception {
        fieldType.recordedCalls.clear();
        fieldType.otherServiceRecordedCalls().clear();

        SearchRequest searchRequest = new SearchRequest(indices.toArray(new String[0])).source(
            new SearchSourceBuilder().query(new MatchQueryBuilder(fieldType.fieldName, "fox"))
        );
        searchRequest.setCcsMinimizeRoundtrips(ccsMinimizeRoundTrips);

        assertResponse(client().search(searchRequest), response -> {
            assertThat(response.getFailedShards(), equalTo(0));
            assertThat(response.getTotalShards(), equalTo(indices.size() * SHARD_COUNT));
            assertThat(response.getHits().getTotalHits().value(), greaterThanOrEqualTo((long) indices.size()));
        });

        List<List<String>> callsSnapshot = List.copyOf(fieldType.recordedCalls);
        assertThat(callsSnapshot, hasSize(expectedCallCount));
        for (List<String> input : callsSnapshot) {
            assertThat(input, equalTo(List.of("fox")));
        }
        assertThat(List.copyOf(fieldType.otherServiceRecordedCalls()), empty());
    }

    private void setupMultiShardClusters() throws Exception {
        setupMultiShardCluster(LOCAL_CLUSTER, LOCAL_INDEX_NAME);
        setupMultiShardCluster(REMOTE_CLUSTER, REMOTE_INDEX_NAME);
        waitUntilRemoteClusterConnected(REMOTE_CLUSTER);
    }

    private void setupMultiShardCluster(String clusterAlias, String indexName) throws Exception {
        Client client = client(clusterAlias);

        Map<String, Object> sparseServiceSettings = new HashMap<>();
        sparseServiceSettings.put("model", randomAlphaOfLength(5));
        sparseServiceSettings.put("api_key", randomAlphaOfLength(5));
        createInferenceEndpoint(client, TaskType.SPARSE_EMBEDDING, SPARSE_INFERENCE_ID, sparseServiceSettings);

        Map<String, Object> denseServiceSettings = new HashMap<>();
        denseServiceSettings.put("model", randomAlphaOfLength(5));
        denseServiceSettings.put("api_key", randomAlphaOfLength(5));
        denseServiceSettings.put("dimensions", DENSE_DIMENSIONS);
        denseServiceSettings.put("similarity", SimilarityMeasure.COSINE);
        denseServiceSettings.put("element_type", DenseVectorFieldMapper.ElementType.FLOAT);
        createInferenceEndpoint(client, TaskType.TEXT_EMBEDDING, DENSE_INFERENCE_ID, denseServiceSettings);

        assertAcked(
            client.admin()
                .indices()
                .prepareCreate(indexName)
                .setSettings(indexSettings(SHARD_COUNT, 0).build())
                .setMapping(
                    Map.of(
                        "properties",
                        Map.of(
                            SPARSE_SEMANTIC_FIELD,
                            semanticTextMapping(SPARSE_INFERENCE_ID),
                            DENSE_SEMANTIC_FIELD,
                            semanticTextMapping(DENSE_INFERENCE_ID)
                        )
                    )
                )
        );
        assertFalse(
            client.admin()
                .cluster()
                .prepareHealth(TEST_REQUEST_TIMEOUT, indexName)
                .setWaitForGreenStatus()
                .setTimeout(TimeValue.timeValueSeconds(10))
                .get()
                .isTimedOut()
        );

        DocWriteResponse docWriteResponse = client.prepareIndex(indexName)
            .setSource(Map.of(SPARSE_SEMANTIC_FIELD, "a quick brown fox", DENSE_SEMANTIC_FIELD, "a quick brown fox"))
            .execute()
            .actionGet();
        assertThat(docWriteResponse.getResult(), equalTo(DocWriteResponse.Result.CREATED));

        BroadcastResponse refreshResponse = client.admin().indices().prepareRefresh(indexName).execute().actionGet();
        assertThat(refreshResponse.getStatus(), is(RestStatus.OK));
    }

    private void setBatchedQueryPhase(boolean batchedQueryPhase) {
        updateBatchedQueryPhase(Settings.builder().put(SearchService.BATCHED_QUERY_PHASE.getKey(), batchedQueryPhase).build());
    }

    private void clearBatchedQueryPhase() {
        updateBatchedQueryPhase(Settings.builder().putNull(SearchService.BATCHED_QUERY_PHASE.getKey()).build());
    }

    private void updateBatchedQueryPhase(Settings settings) {
        assertAcked(
            client(LOCAL_CLUSTER).admin()
                .cluster()
                .prepareUpdateSettings(TEST_REQUEST_TIMEOUT, TEST_REQUEST_TIMEOUT)
                .setPersistentSettings(settings)
        );
    }
}
