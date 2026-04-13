/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.synonyms;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DelegatingActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.TransportClusterHealthAction;
import org.elasticsearch.action.admin.indices.analyze.ReloadAnalyzersRequest;
import org.elasticsearch.action.admin.indices.analyze.ReloadAnalyzersResponse;
import org.elasticsearch.action.admin.indices.analyze.TransportReloadAnalyzersAction;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.IndexDocFailureStoreStatus;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.ClosePointInTimeRequest;
import org.elasticsearch.action.search.OpenPointInTimeRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.TransportClosePointInTimeAction;
import org.elasticsearch.action.search.TransportOpenPointInTimeAction;
import org.elasticsearch.action.search.TransportSearchAction;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.routing.Preference;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.indices.IndexCreationException;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.bucket.filter.Filters;
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.FiltersAggregator;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.PointInTimeBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.elasticsearch.index.mapper.MapperService.SINGLE_MAPPING_NAME;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;

/**
 * Manages synonyms performing operations on the system index
 */
public class SynonymsManagementAPIService {

    private static final String SYNONYMS_INDEX_NAME_PATTERN = ".synonyms-*";
    private static final int SYNONYMS_INDEX_FORMAT = 2;
    static final String SYNONYMS_INDEX_CONCRETE_NAME = ".synonyms-" + SYNONYMS_INDEX_FORMAT;
    private static final String SYNONYMS_ALIAS_NAME = ".synonyms";
    public static final String SYNONYMS_FEATURE_NAME = "synonyms";
    // Stores the synonym set the rule belongs to
    public static final String SYNONYMS_SET_FIELD = "synonyms_set";
    // Stores the synonym rule
    public static final String SYNONYMS_FIELD = SynonymRule.SYNONYMS_FIELD.getPreferredName();
    // Field that stores either SYNONYM_RULE_OBJECT_TYPE or SYNONYM_SET_OBJECT_TYPE
    private static final String OBJECT_TYPE_FIELD = "type";
    // Identifies synonym rule objects stored in the index
    private static final String SYNONYM_RULE_OBJECT_TYPE = "synonym_rule";
    // Identifies synonym set objects stored in the index
    private static final String SYNONYM_SET_OBJECT_TYPE = "synonym_set";
    private static final String SYNONYM_RULE_ID_SEPARATOR = "|";
    private static final int MAX_SYNONYM_RULES = 10_000;
    private static final TimeValue PIT_KEEP_ALIVE = TimeValue.timeValueSeconds(60);
    static final int PIT_BATCH_SIZE = 10_000;
    private static final String SYNONYM_RULE_ID_FIELD = SynonymRule.ID_FIELD.getPreferredName();
    private static final String SYNONYM_SETS_AGG_NAME = "synonym_sets_aggr";
    private static final String RULE_COUNT_AGG_NAME = "rule_count";
    private static final String RULE_COUNT_FILTER_KEY = "synonym_rules";
    private static final int SYNONYMS_INDEX_MAPPINGS_VERSION = 2;
    private static final String GENERATION_FIELD = "generation";
    private static final String CURRENT_GENERATION_FIELD = "current_generation";
    public static final int INDEX_SEARCHABLE_TIMEOUT_SECONDS = 30;

    private final int maxSynonymRules;
    private final int pitBatchSize;

    // Package private for testing
    static Logger logger = LogManager.getLogger(SynonymsManagementAPIService.class);

    private final Client client;

    public static final String SYNONYMS_ORIGIN = "synonyms";
    public static final SystemIndexDescriptor SYNONYMS_DESCRIPTOR = SystemIndexDescriptor.builder()
        .setIndexPattern(SYNONYMS_INDEX_NAME_PATTERN)
        .setDescription("Synonyms index for synonyms managed through APIs")
        .setPrimaryIndex(SYNONYMS_INDEX_CONCRETE_NAME)
        .setAliasName(SYNONYMS_ALIAS_NAME)
        .setIndexFormat(SYNONYMS_INDEX_FORMAT)
        .setMappings(mappings())
        .setSettings(settings())
        .setOrigin(SYNONYMS_ORIGIN)
        .build();

    public SynonymsManagementAPIService(Client client) {
        this(client, MAX_SYNONYM_RULES, PIT_BATCH_SIZE);
    }

    // Used for testing, so we don't need to test for MAX_SYNONYM_RULES and put unnecessary memory pressure on the test cluster
    SynonymsManagementAPIService(Client client, int maxSynonymRules) {
        this(client, maxSynonymRules, PIT_BATCH_SIZE);
    }

    // Used for testing PIT pagination behavior with a small batch size to force multiple iterations
    SynonymsManagementAPIService(Client client, int maxSynonymRules, int pitBatchSize) {
        this.client = new OriginSettingClient(client, SYNONYMS_ORIGIN);

        this.maxSynonymRules = maxSynonymRules;
        this.pitBatchSize = pitBatchSize;
    }

    /* The synonym index stores two object types:
    - Synonym rules:
        - SYNONYM_RULE_ID_FIELD contains the synonym rule ID
        - SYNONYMS_FIELD contains the synonyms
        - SYNONYMS_SET_FIELD contains the synonym set the rule belongs to
        - OBJECT_TYPE_FIELD contains SYNONYM_RULE_OBJECT_TYPE
        - The id is calculated using internalSynonymRuleId method
    - Synonym sets:
        - SYNONYMS_SET_FIELD contains the synonym set name
        - OBJECT_TYPE_FIELD contains SYNONYM_SET_OBJECT_TYPE
        - The id is the synonym set name
     */
    private static XContentBuilder mappings() {
        try {
            XContentBuilder builder = jsonBuilder();
            builder.startObject();
            {
                builder.startObject(SINGLE_MAPPING_NAME);
                {
                    builder.startObject("_meta");
                    {
                        builder.field("version", Version.CURRENT.toString());
                        builder.field(SystemIndexDescriptor.VERSION_META_KEY, SYNONYMS_INDEX_MAPPINGS_VERSION);
                    }
                    builder.endObject();
                    builder.field("dynamic", "strict");
                    builder.startObject("properties");
                    {
                        builder.startObject(SYNONYM_RULE_ID_FIELD);
                        {
                            builder.field("type", "keyword");
                        }
                        builder.endObject();
                        builder.startObject(SYNONYMS_FIELD);
                        {
                            builder.field("type", "match_only_text");
                        }
                        builder.endObject();
                        builder.startObject(SYNONYMS_SET_FIELD);
                        {
                            builder.field("type", "keyword");
                        }
                        builder.endObject();
                        builder.startObject(OBJECT_TYPE_FIELD);
                        {
                            builder.field("type", "keyword");
                        }
                        builder.endObject();
                        builder.startObject(GENERATION_FIELD);
                        {
                            builder.field("type", "keyword");
                        }
                        builder.endObject();
                        builder.startObject(CURRENT_GENERATION_FIELD);
                        {
                            builder.field("type", "keyword");
                        }
                        builder.endObject();
                    }
                    builder.endObject();
                }
                builder.endObject();
            }
            builder.endObject();
            return builder;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build mappings for " + SYNONYMS_INDEX_CONCRETE_NAME, e);
        }
    }

    /**
     * Returns all synonym sets with their rule counts, including empty synonym sets.
     * @param from The index of the first synonym set to return
     * @param size The number of synonym sets to return
     * @param listener The listener to return the synonym sets to
     */
    public void getSynonymsSets(int from, int size, ActionListener<PagedResult<SynonymSetSummary>> listener) {
        BoolQueryBuilder synonymSetQuery = QueryBuilders.boolQuery()
            .should(QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_SET_OBJECT_TYPE))
            .should(QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_RULE_OBJECT_TYPE))
            .minimumShouldMatch(1);

        // Aggregation query to count only synonym rules (excluding synonym set objects)
        FiltersAggregationBuilder ruleCountAggregation = new FiltersAggregationBuilder(
            RULE_COUNT_AGG_NAME,
            new FiltersAggregator.KeyedFilter(RULE_COUNT_FILTER_KEY, QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_RULE_OBJECT_TYPE))
        );

        client.prepareSearch(SYNONYMS_ALIAS_NAME)
            .setSize(0)
            // Retrieves aggregated synonym rules for each synonym set, excluding the synonym set object type
            .setQuery(synonymSetQuery)
            .addAggregation(
                new TermsAggregationBuilder(SYNONYM_SETS_AGG_NAME).field(SYNONYMS_SET_FIELD)
                    .order(BucketOrder.key(true))
                    .size(maxSynonymRules)
                    .subAggregation(ruleCountAggregation)
            )
            .setPreference(Preference.LOCAL.type())
            .execute(ActionListener.wrap(searchResponse -> {
                Terms termsAggregation = searchResponse.getAggregations().get(SYNONYM_SETS_AGG_NAME);
                List<? extends Terms.Bucket> buckets = termsAggregation.getBuckets();
                SynonymSetSummary[] synonymSetSummaries = buckets.stream().skip(from).limit(size).map(bucket -> {
                    Filters ruleCountFilters = bucket.getAggregations().get(RULE_COUNT_AGG_NAME);
                    Filters.Bucket ruleCountBucket = ruleCountFilters.getBucketByKey(RULE_COUNT_FILTER_KEY);
                    return new SynonymSetSummary(ruleCountBucket.getDocCount(), bucket.getKeyAsString());
                }).toArray(SynonymSetSummary[]::new);

                listener.onResponse(new PagedResult<>(buckets.size(), synonymSetSummaries));
            }, e -> {
                final Throwable cause = ExceptionsHelper.unwrapCause(e);
                if (cause instanceof IndexNotFoundException) {
                    // If System index has not been created yet, no synonym sets have been stored
                    listener.onResponse(new PagedResult<>(0L, new SynonymSetSummary[0]));
                    return;
                }

                listener.onFailure(e);
            }));
    }

    /**
     * Retrieves all synonym rules for a synonym set using PIT + search_after.
     * Results are fetched iteratively in batches of {@value PIT_BATCH_SIZE} until exhausted.
     *
     * @param synonymSetId the synonym set to load
     * @param listener     receives the complete set of rules
     */
    public void getSynonymSetRules(String synonymSetId, ActionListener<PagedResult<SynonymRule>> listener) {
        client.prepareGet(SYNONYMS_ALIAS_NAME, synonymSetId).execute(ActionListener.wrap(getResponse -> {
            String currentGeneration = null;
            if (getResponse.isExists()) {
                Object val = getResponse.getSourceAsMap().get(CURRENT_GENERATION_FIELD);
                if (val instanceof String s) {
                    currentGeneration = s;
                }
            }
            final String generation = currentGeneration;
            OpenPointInTimeRequest pitRequest = new OpenPointInTimeRequest(SYNONYMS_ALIAS_NAME).keepAlive(PIT_KEEP_ALIVE);
            client.execute(
                TransportOpenPointInTimeAction.TYPE,
                pitRequest,
                new DelegatingIndexNotFoundActionListener<>(synonymSetId, listener, (l, pitResponse) -> {
                    fetchPageWithPit(synonymSetId, generation, pitResponse.getPointInTimeId(), null, new ArrayList<>(), l);
                })
            );
        }, e -> {
            Throwable cause = ExceptionsHelper.unwrapCause(e);
            if (cause instanceof IndexNotFoundException) {
                listener.onFailure(new ResourceNotFoundException("synonyms set [" + synonymSetId + "] not found"));
            } else {
                listener.onFailure(e);
            }
        }));
    }

    private void fetchPageWithPit(
        String synonymSetId,
        String generation,
        BytesReference pitId,
        Object[] searchAfter,
        List<SynonymRule> accumulated,
        ActionListener<PagedResult<SynonymRule>> listener
    ) {
        BoolQueryBuilder query = QueryBuilders.boolQuery()
            .must(QueryBuilders.termQuery(SYNONYMS_SET_FIELD, synonymSetId))
            .filter(QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_RULE_OBJECT_TYPE));
        if (generation != null) {
            query.filter(QueryBuilders.termQuery(GENERATION_FIELD, generation));
        }
        SearchSourceBuilder source = new SearchSourceBuilder().query(query)
            .size(pitBatchSize)
            .sort(SortBuilders.fieldSort(SYNONYM_RULE_ID_FIELD).order(SortOrder.ASC))
            .sort(SortBuilders.fieldSort("_shard_doc").order(SortOrder.ASC))
            .trackTotalHits(true)
            .fetchSource(false)
            .fetchField(SYNONYM_RULE_ID_FIELD)
            .fetchField(SYNONYMS_FIELD)
            .pointInTimeBuilder(new PointInTimeBuilder(pitId).setKeepAlive(PIT_KEEP_ALIVE));

        if (searchAfter != null) {
            source.searchAfter(searchAfter);
        }

        AtomicReference<BytesReference> currentPitId = new AtomicReference<>(pitId);
        client.execute(TransportSearchAction.TYPE, new SearchRequest().source(source), ActionListener.wrap(response -> {
            SearchHit[] hits = response.getHits().getHits();
            long totalHits = response.getHits().getTotalHits().value();
            assert response.pointInTimeId() != null;
            currentPitId.set(response.pointInTimeId());

            if (hits.length == 0) {
                if (accumulated.isEmpty()) {
                    closePitAndThen(currentPitId.get(), () -> checkSynonymSetExists(synonymSetId, listener.delegateFailure((l, ignored) -> {
                        l.onResponse(new PagedResult<>(0, new SynonymRule[0]));
                    })));
                } else {
                    PagedResult<SynonymRule> result = new PagedResult<>(totalHits, accumulated.toArray(new SynonymRule[0]));
                    closePitAndThen(currentPitId.get(), () -> listener.onResponse(result));
                }
                return;
            }

            if (searchAfter == null && totalHits > maxSynonymRules) {
                logger.warn(
                    "The number of synonym rules in the synonym set [{}] exceeds the maximum allowed."
                        + " Inconsistent synonyms results may occur",
                    synonymSetId
                );
            }

            for (SearchHit hit : hits) {
                accumulated.add(hitToSynonymRule(hit));
                if (accumulated.size() >= maxSynonymRules) {
                    PagedResult<SynonymRule> result = new PagedResult<>(totalHits, accumulated.toArray(new SynonymRule[0]));
                    closePitAndThen(currentPitId.get(), () -> listener.onResponse(result));
                    return;
                }
            }

            Object[] lastSortValues = hits[hits.length - 1].getSortValues();
            fetchPageWithPit(synonymSetId, generation, currentPitId.get(), lastSortValues, accumulated, listener);
        }, e -> { closePitAndThen(currentPitId.get(), () -> listener.onFailure(e)); }));
    }

    private void closePitAndThen(BytesReference pitId, Runnable andThen) {
        assert pitId != null;
        client.execute(TransportClosePointInTimeAction.TYPE, new ClosePointInTimeRequest(pitId), ActionListener.wrap(r -> {
            // specify system_read so that the response isn't completed on the generic thread pool
            client.threadPool().executor(ThreadPool.Names.SYSTEM_READ).execute(andThen);
        }, e -> {
            logger.warn("Failed to close PIT context", e);
            client.threadPool().executor(ThreadPool.Names.SYSTEM_READ).execute(andThen);
        }));
    }

    /**
     * Retrieves synonym rules for a synonym set, with pagination support. This method does not check that pagination is
     * correct in terms of the max_result_window setting.
     *
     * @param synonymSetId
     * @param from
     * @param size
     * @param listener
     */
    public void getSynonymSetRules(String synonymSetId, int from, int size, ActionListener<PagedResult<SynonymRule>> listener) {
        // Retrieves synonym rules, excluding the synonym set object type
        client.prepareSearch(SYNONYMS_ALIAS_NAME)
            .setQuery(
                QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery(SYNONYMS_SET_FIELD, synonymSetId))
                    .filter(QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_RULE_OBJECT_TYPE))
            )
            .setFrom(from)
            .setSize(size)
            .addSort("id", SortOrder.ASC)
            .setPreference(Preference.LOCAL.type())
            .setTrackTotalHits(true)
            .setFetchSource(false)
            .addFetchField(SYNONYM_RULE_ID_FIELD)
            .addFetchField(SYNONYMS_FIELD)
            .execute(new DelegatingIndexNotFoundActionListener<>(synonymSetId, listener, (searchListener, searchResponse) -> {
                final long totalSynonymRules = searchResponse.getHits().getTotalHits().value();
                // If there are no rules, check that the synonym set actually exists to return the proper error
                if (totalSynonymRules == 0) {
                    checkSynonymSetExists(synonymSetId, searchListener.delegateFailure((existsListener, response) -> {
                        searchListener.onResponse(new PagedResult<>(0, new SynonymRule[0]));
                    }));
                    return;
                }
                final SynonymRule[] synonymRules = Arrays.stream(searchResponse.getHits().getHits())
                    .map(SynonymsManagementAPIService::hitToSynonymRule)
                    .toArray(SynonymRule[]::new);
                searchListener.onResponse(new PagedResult<>(totalSynonymRules, synonymRules));
            }));
    }

    private static SynonymRule hitToSynonymRule(SearchHit hit) {
        String id = hit.field(SYNONYM_RULE_ID_FIELD).getValue();
        String synonyms = hit.field(SYNONYMS_FIELD).getValue();
        return new SynonymRule(id, synonyms);
    }

    private static void logUniqueFailureMessagesWithIndices(List<BulkItemResponse.Failure> bulkFailures) {
        // check if logger is at least debug
        if (logger.isDebugEnabled() == false) {
            return;
        }
        Map<String, List<BulkItemResponse.Failure>> uniqueFailureMessages = bulkFailures.stream()
            .collect(Collectors.groupingBy(BulkItemResponse.Failure::getMessage));
        // log each unique failure with their associated indices and the first stacktrace
        uniqueFailureMessages.forEach((failureMessage, failures) -> {
            logger.debug(
                "Error updating synonyms: [{}], indices: [{}], stacktrace: [{}]",
                failureMessage,
                failures.stream().map(BulkItemResponse.Failure::getIndex).collect(Collectors.joining(",")),
                ExceptionsHelper.formatStackTrace(failures.get(0).getCause().getStackTrace())
            );
        });
    }

    public void putSynonymsSet(
        String synonymSetId,
        SynonymRule[] synonymsSet,
        boolean refresh,
        ActionListener<SynonymsReloadResult> listener
    ) {
        if (synonymsSet.length > maxSynonymRules) {
            listener.onFailure(
                new IllegalArgumentException("The number of synonyms rules in a synonym set cannot exceed " + maxSynonymRules)
            );
            return;
        }
        // Read the set document to determine existence and capture seq_no for OCC flip
        client.prepareGet(SYNONYMS_ALIAS_NAME, synonymSetId).execute(ActionListener.wrap(getResponse -> {
            boolean created = getResponse.isExists() == false;
            long seqNo = getResponse.isExists() ? getResponse.getSeqNo() : SequenceNumbers.UNASSIGNED_SEQ_NO;
            long primaryTerm = getResponse.isExists() ? getResponse.getPrimaryTerm() : SequenceNumbers.UNASSIGNED_PRIMARY_TERM;
            doGenerationWrite(synonymSetId, synonymsSet, refresh, created, seqNo, primaryTerm, listener);
        }, e -> {
            Throwable cause = ExceptionsHelper.unwrapCause(e);
            if (cause instanceof IndexNotFoundException) {
                doGenerationWrite(
                    synonymSetId,
                    synonymsSet,
                    refresh,
                    true,
                    SequenceNumbers.UNASSIGNED_SEQ_NO,
                    SequenceNumbers.UNASSIGNED_PRIMARY_TERM,
                    listener
                );
            } else {
                listener.onFailure(e);
            }
        }));
    }

    private void doGenerationWrite(
        String synonymSetId,
        SynonymRule[] synonymsSet,
        boolean refresh,
        boolean created,
        long seqNo,
        long primaryTerm,
        ActionListener<SynonymsReloadResult> listener
    ) {
        String writeToken = UUIDs.base64UUID();
        ActionListener<Void> afterBulkListener = listener.delegateFailure((l, ignored) -> {
            // OCC flip the set document to activate the new generation
            IndexRequest flipRequest;
            try {
                flipRequest = createSynonymSetIndexRequest(synonymSetId, writeToken);
            } catch (IOException e) {
                asyncDeleteOrphanedGeneration(synonymSetId, writeToken);
                l.onFailure(e);
                return;
            }
            if (created) {
                flipRequest.opType(DocWriteRequest.OpType.CREATE);
            } else {
                flipRequest.setIfSeqNo(seqNo).setIfPrimaryTerm(primaryTerm);
            }
            flipRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
            client.index(flipRequest, ActionListener.wrap(indexResponse -> {
                UpdateSynonymsResultStatus status = created ? UpdateSynonymsResultStatus.CREATED : UpdateSynonymsResultStatus.UPDATED;
                checkIndexSearchableAndReloadAnalyzers(
                    synonymSetId,
                    refresh,
                    false,
                    status,
                    l.delegateFailure((reloadListener, reloadResult) -> {
                        // Async cleanup of stale generations after reload — no added latency on reload path
                        asyncDeleteStaleGenerations(synonymSetId, writeToken);
                        reloadListener.onResponse(reloadResult);
                    })
                );
            }, e -> {
                asyncDeleteOrphanedGeneration(synonymSetId, writeToken);
                l.onFailure(e);
            }));
        });

        if (synonymsSet.length == 0) {
            // No rules to bulk-index; skip directly to the flip step.
            afterBulkListener.onResponse(null);
        } else {
            bulkInsertWithGeneration(synonymSetId, synonymsSet, writeToken, listener.delegateFailure((l, bulkResponse) -> {
                if (bulkResponse.hasFailures()) {
                    asyncDeleteOrphanedGeneration(synonymSetId, writeToken);
                    logUniqueFailureMessagesWithIndices(
                        Arrays.stream(bulkResponse.getItems())
                            .filter(BulkItemResponse::isFailed)
                            .map(BulkItemResponse::getFailure)
                            .collect(Collectors.toList())
                    );
                    l.onFailure(new ElasticsearchException("Error updating synonyms: " + bulkResponse.buildFailureMessage()));
                    return;
                }
                afterBulkListener.onResponse(null);
            }));
        }
    }

    private void bulkInsertWithGeneration(
        String synonymSetId,
        SynonymRule[] synonymsSet,
        String writeToken,
        ActionListener<BulkResponse> listener
    ) {
        BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();
        try {
            for (SynonymRule synonymRule : synonymsSet) {
                bulkRequestBuilder.add(createSynonymRuleIndexRequest(synonymSetId, synonymRule, writeToken));
            }
        } catch (IOException ex) {
            listener.onFailure(ex);
            return;
        }
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).execute(listener);
    }

    // Open for testing adding more synonyms set than the limit allows for.
    // NOTE: This method bypasses generation tracking and is for testing only.
    void bulkUpdateSynonymsSet(String synonymSetId, SynonymRule[] synonymsSet, ActionListener<BulkResponse> listener) {
        BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();
        try {
            // Insert synonym set object
            bulkRequestBuilder.add(createSynonymSetIndexRequest(synonymSetId, null));
            // Insert synonym rules
            for (SynonymRule synonymRule : synonymsSet) {
                bulkRequestBuilder.add(createSynonymRuleIndexRequest(synonymSetId, synonymRule, null));
            }
        } catch (IOException ex) {
            listener.onFailure(ex);
        }

        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).execute(listener);
    }

    public void putSynonymRule(
        String synonymsSetId,
        SynonymRule synonymRule,
        boolean refresh,
        ActionListener<SynonymsReloadResult> listener
    ) {
        checkSynonymSetExists(synonymsSetId, listener.delegateFailureAndWrap((l1, ignored) -> {
            client.prepareGet(SYNONYMS_ALIAS_NAME, synonymsSetId).execute(l1.delegateFailureAndWrap((l2, getResponse) -> {
                Object val = getResponse.isExists() ? getResponse.getSourceAsMap().get(CURRENT_GENERATION_FIELD) : null;
                String currentGeneration = val instanceof String s ? s : null;
                BoolQueryBuilder queryFilter = QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery(SYNONYMS_SET_FIELD, synonymsSetId))
                    .filter(QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_RULE_OBJECT_TYPE));
                if (synonymRule.id() != null) {
                    queryFilter.mustNot(QueryBuilders.termQuery(SYNONYM_RULE_ID_FIELD, synonymRule.id()));
                }
                client.prepareSearch(SYNONYMS_ALIAS_NAME)
                    .setQuery(queryFilter)
                    .setSize(0)
                    .setPreference(Preference.LOCAL.type())
                    .setTrackTotalHits(true)
                    .execute(l2.delegateFailureAndWrap((l3, searchResponse) -> {
                        if (searchResponse.getHits().getTotalHits().value() >= maxSynonymRules) {
                            l3.onFailure(
                                new IllegalArgumentException(
                                    "The number of synonym rules in a synonyms set cannot exceed " + maxSynonymRules
                                )
                            );
                            return;
                        }
                        IndexRequest indexRequest = createSynonymRuleIndexRequest(synonymsSetId, synonymRule, currentGeneration)
                            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
                        client.index(indexRequest, l3.delegateFailure((l4, indexResponse) -> {
                            UpdateSynonymsResultStatus updateStatus = indexResponse.status() == org.elasticsearch.rest.RestStatus.CREATED
                                ? UpdateSynonymsResultStatus.CREATED
                                : UpdateSynonymsResultStatus.UPDATED;
                            checkIndexSearchableAndReloadAnalyzers(synonymsSetId, refresh, false, updateStatus, l4);
                        }));
                    }));
            }));
        }));
    }

    public void getSynonymRule(String synonymSetId, String synonymRuleId, ActionListener<SynonymRule> listener) {
        checkSynonymSetExists(
            synonymSetId,
            listener.delegateFailure(
                (l1, obj) -> client.prepareGet(SYNONYMS_ALIAS_NAME, internalSynonymRuleId(synonymSetId, synonymRuleId))
                    .execute(l1.delegateFailure((l2, getResponse) -> {
                        if (getResponse.isExists() == false) {
                            l2.onFailure(new ResourceNotFoundException("synonym rule [" + synonymRuleId + "] not found"));
                            return;
                        }
                        Map<String, Object> source = getResponse.getSourceAsMap();
                        l2.onResponse(new SynonymRule((String) source.get(SYNONYM_RULE_ID_FIELD), (String) source.get(SYNONYMS_FIELD)));
                    }))
            )
        );
    }

    public void deleteSynonymRule(
        String synonymsSetId,
        String synonymRuleId,
        boolean refresh,
        ActionListener<SynonymsReloadResult> listener
    ) {
        client.prepareDelete(SYNONYMS_ALIAS_NAME, internalSynonymRuleId(synonymsSetId, synonymRuleId))
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            .execute(new DelegatingIndexNotFoundActionListener<>(synonymsSetId, listener, (l, deleteResponse) -> {
                if (deleteResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                    // When not found, check whether it's the synonym set not existing
                    checkSynonymSetExists(
                        synonymsSetId,
                        l.delegateFailure(
                            (checkListener, obj) -> checkListener.onFailure(
                                new ResourceNotFoundException(
                                    "synonym rule [" + synonymRuleId + "] not found on synonyms set [" + synonymsSetId + "]"
                                )
                            )
                        )
                    );
                    return;
                }

                if (refresh) {
                    reloadAnalyzers(synonymsSetId, false, UpdateSynonymsResultStatus.DELETED, listener);
                } else {
                    listener.onResponse(new SynonymsReloadResult(UpdateSynonymsResultStatus.DELETED, null));
                }
            }));
    }

    private static IndexRequest createSynonymRuleIndexRequest(String synonymsSetId, SynonymRule synonymRule, String generation)
        throws IOException {
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            builder.startObject();
            {
                builder.field(SYNONYMS_SET_FIELD, synonymsSetId);
                builder.field(SYNONYM_RULE_ID_FIELD, synonymRule.id());
                builder.field(SYNONYMS_FIELD, synonymRule.synonyms());
                builder.field(OBJECT_TYPE_FIELD, SYNONYM_RULE_OBJECT_TYPE);
                builder.field(GENERATION_FIELD, generation);
            }
            builder.endObject();

            return new IndexRequest(SYNONYMS_ALIAS_NAME).id(internalSynonymRuleId(synonymsSetId, synonymRule.id()))
                .opType(DocWriteRequest.OpType.INDEX)
                .source(builder);
        }
    }

    private static IndexRequest createSynonymSetIndexRequest(String synonymsSetId, String writeToken) throws IOException {
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            builder.startObject();
            {
                builder.field(SYNONYMS_SET_FIELD, synonymsSetId);
                builder.field(OBJECT_TYPE_FIELD, SYNONYM_SET_OBJECT_TYPE);
                builder.field(CURRENT_GENERATION_FIELD, writeToken);
            }
            builder.endObject();

            return new IndexRequest(SYNONYMS_ALIAS_NAME).id(synonymsSetId).opType(DocWriteRequest.OpType.INDEX).source(builder);
        }
    }

    private void asyncDeleteStaleGenerations(String synonymSetId, String currentToken) {
        DeleteByQueryRequest dbqRequest = new DeleteByQueryRequest(SYNONYMS_ALIAS_NAME).setQuery(
            QueryBuilders.boolQuery()
                .must(QueryBuilders.termQuery(SYNONYMS_SET_FIELD, synonymSetId))
                .filter(QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_RULE_OBJECT_TYPE))
                .mustNot(QueryBuilders.termQuery(GENERATION_FIELD, currentToken))
        ).setRefresh(false).setIndicesOptions(IndicesOptions.fromOptions(true, true, false, false));

        client.execute(
            DeleteByQueryAction.INSTANCE,
            dbqRequest,
            ActionListener.wrap(
                r -> logger.debug("Cleaned up [{}] stale synonym rules for set [{}]", r.getDeleted(), synonymSetId),
                e -> logger.warn("Failed to clean up stale synonym rules for set [{}]", synonymSetId, e)
            )
        );
    }

    private void asyncDeleteOrphanedGeneration(String synonymSetId, String orphanToken) {
        DeleteByQueryRequest dbqRequest = new DeleteByQueryRequest(SYNONYMS_ALIAS_NAME).setQuery(
            QueryBuilders.boolQuery()
                .must(QueryBuilders.termQuery(SYNONYMS_SET_FIELD, synonymSetId))
                .filter(QueryBuilders.termQuery(GENERATION_FIELD, orphanToken))
        ).setRefresh(false).setIndicesOptions(IndicesOptions.fromOptions(true, true, false, false));

        client.execute(
            DeleteByQueryAction.INSTANCE,
            dbqRequest,
            ActionListener.wrap(
                r -> logger.debug(
                    "Cleaned up [{}] orphaned synonym rules for set [{}] token [{}]",
                    r.getDeleted(),
                    synonymSetId,
                    orphanToken
                ),
                e -> logger.warn("Failed to clean up orphaned synonym rules for set [{}] token [{}]", synonymSetId, orphanToken, e)
            )
        );
    }

    private <T> void checkSynonymSetExists(String synonymsSetId, ActionListener<T> listener) {
        // Get the document with the synonym set ID
        client.prepareGet(SYNONYMS_ALIAS_NAME, synonymsSetId)
            .execute(new DelegatingIndexNotFoundActionListener<>(synonymsSetId, listener, (l, getResponse) -> {
                if (getResponse.isExists() == false) {
                    l.onFailure(new ResourceNotFoundException("synonyms set [" + synonymsSetId + "] not found"));
                    return;
                }
                l.onResponse(null);
            }));
    }

    // Deletes a synonym set rules, using the supplied listener
    private void deleteSynonymsSetObjects(String synonymSetId, ActionListener<BulkByScrollResponse> listener) {
        DeleteByQueryRequest dbqRequest = new DeleteByQueryRequest(SYNONYMS_ALIAS_NAME).setQuery(
            QueryBuilders.termQuery(SYNONYMS_SET_FIELD, synonymSetId)
        ).setRefresh(true).setIndicesOptions(IndicesOptions.fromOptions(true, true, false, false));

        client.execute(DeleteByQueryAction.INSTANCE, dbqRequest, listener);
    }

    public void deleteSynonymsSet(String synonymSetId, ActionListener<AcknowledgedResponse> listener) {

        // Previews reloading the resource to understand its usage on indices
        reloadAnalyzers(synonymSetId, true, null, listener.delegateFailure((reloadListener, reloadResult) -> {
            Map<String, ReloadAnalyzersResponse.ReloadDetails> reloadDetails = reloadResult.reloadAnalyzersResponse.getReloadDetails();
            if (reloadDetails.isEmpty() == false) {
                Set<String> indices = reloadDetails.entrySet()
                    .stream()
                    .map(entry -> entry.getValue().getIndexName())
                    .collect(Collectors.toSet());
                reloadListener.onFailure(
                    new IllegalArgumentException(
                        "synonyms set ["
                            + synonymSetId
                            + "] cannot be deleted as it is used in the following indices: "
                            + String.join(", ", indices)
                    )
                );
                return;
            }

            deleteSynonymsSetObjects(synonymSetId, listener.delegateFailure((deleteObjectsListener, bulkByScrollResponse) -> {
                if (bulkByScrollResponse.getDeleted() == 0) {
                    // If nothing was deleted, synonym set did not exist
                    deleteObjectsListener.onFailure(new ResourceNotFoundException("synonyms set [" + synonymSetId + "] not found"));
                    return;
                }
                final List<BulkItemResponse.Failure> bulkFailures = bulkByScrollResponse.getBulkFailures();
                if (bulkFailures.isEmpty() == false) {
                    deleteObjectsListener.onFailure(
                        new InvalidParameterException(
                            "Error deleting synonyms set: "
                                + bulkFailures.stream().map(BulkItemResponse.Failure::getMessage).collect(Collectors.joining("\n"))
                        )
                    );
                    return;
                }

                deleteObjectsListener.onResponse(AcknowledgedResponse.of(true));
            }));
        }));
    }

    private <T> void checkIndexSearchableAndReloadAnalyzers(
        String synonymSetId,
        boolean refresh,
        boolean preview,
        UpdateSynonymsResultStatus synonymsOperationResult,
        ActionListener<SynonymsReloadResult> listener
    ) {

        if (refresh == false) {
            // If not refreshing, we don't need to reload analyzers
            listener.onResponse(new SynonymsReloadResult(synonymsOperationResult, null));
            return;
        }

        // Check synonyms index is searchable before reloading, to ensure analyzers are able to load the changed information
        checkSynonymsIndexHealth(listener.delegateFailure((l, response) -> {
            if (response.isTimedOut()) {
                l.onFailure(
                    new IndexCreationException(
                        "synonyms index ["
                            + SYNONYMS_ALIAS_NAME
                            + "] is not searchable. "
                            + response.getActiveShardsPercent()
                            + "% shards are active",
                        null
                    )
                );
                return;
            }

            reloadAnalyzers(synonymSetId, preview, synonymsOperationResult, listener);
        }));
    }

    private void reloadAnalyzers(
        String synonymSetId,
        boolean preview,
        UpdateSynonymsResultStatus synonymsOperationResult,
        ActionListener<SynonymsReloadResult> listener
    ) {
        // auto-reload all reloadable analyzers (currently only those that use updateable synonym or keyword_marker filters)
        ReloadAnalyzersRequest reloadAnalyzersRequest = new ReloadAnalyzersRequest(synonymSetId, preview, "*");
        client.execute(
            TransportReloadAnalyzersAction.TYPE,
            reloadAnalyzersRequest,
            listener.safeMap(reloadResponse -> new SynonymsReloadResult(synonymsOperationResult, reloadResponse))
        );
    }

    // Allows checking failures in tests
    void checkSynonymsIndexHealth(ActionListener<ClusterHealthResponse> listener) {
        ClusterHealthRequest healthRequest = new ClusterHealthRequest(
            TimeValue.timeValueSeconds(INDEX_SEARCHABLE_TIMEOUT_SECONDS),
            SYNONYMS_ALIAS_NAME
        ).waitForGreenStatus();

        client.execute(TransportClusterHealthAction.TYPE, healthRequest, listener);
    }

    // Retrieves the internal synonym rule ID to store it in the index. As the same synonym rule ID
    // can be used in different synonym sets, we prefix the ID with the synonym set to avoid collisions
    private static String internalSynonymRuleId(String synonymsSetId, String synonymRuleId) {
        return synonymsSetId + SYNONYM_RULE_ID_SEPARATOR + synonymRuleId;
    }

    private static Settings settings() {
        return Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_AUTO_EXPAND_REPLICAS, "0-1")
            .put(IndexMetadata.INDEX_FORMAT_SETTING.getKey(), SYNONYMS_INDEX_FORMAT)
            .build();
    }

    public enum UpdateSynonymsResultStatus {
        CREATED,
        UPDATED,
        DELETED
    }

    public record SynonymsReloadResult(
        UpdateSynonymsResultStatus synonymsOperationResult,
        ReloadAnalyzersResponse reloadAnalyzersResponse
    ) {}

    // Listeners that checks failures for IndexNotFoundException, and transforms them in ResourceNotFoundException,
    // invoking onFailure on the delegate listener
    static class DelegatingIndexNotFoundActionListener<T, R> extends DelegatingActionListener<T, R> {

        private final BiConsumer<ActionListener<R>, T> bc;
        private final String synonymSetId;

        DelegatingIndexNotFoundActionListener(String synonymSetId, ActionListener<R> delegate, BiConsumer<ActionListener<R>, T> bc) {
            super(delegate);
            this.bc = bc;
            this.synonymSetId = synonymSetId;
        }

        @Override
        public void onResponse(T t) {
            bc.accept(delegate, t);
        }

        @Override
        public void onFailure(Exception e) {
            Throwable cause = ExceptionsHelper.unwrapCause(e);
            if (cause instanceof IndexDocFailureStoreStatus.ExceptionWithFailureStoreStatus) {
                cause = cause.getCause();
            }
            if (cause instanceof IndexNotFoundException) {
                delegate.onFailure(new ResourceNotFoundException("synonyms set [" + synonymSetId + "] not found"));
                return;
            }
            delegate.onFailure(e);
        }
    }
}
