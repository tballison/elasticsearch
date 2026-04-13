/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.synonyms;

import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.index.mapper.extras.MapperExtrasPlugin;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.reindex.ReindexPlugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Before;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.elasticsearch.action.synonyms.SynonymsTestUtils.randomSynonymsSet;
import static org.elasticsearch.synonyms.SynonymsManagementAPIService.SYNONYMS_ORIGIN;
import static org.elasticsearch.synonyms.SynonymsManagementAPIService.SYNONYMS_SET_FIELD;

/**
 * Integration tests for the generation-based write (gen_token) approach in
 * {@link SynonymsManagementAPIService}.
 *
 * <p>These tests verify the internal concurrency invariants introduced by the gen_token
 * design: rule documents carry a {@code generation} field, the set document carries a
 * {@code current_generation} pointer, reads filter to the current generation, and stale
 * or orphaned rules are eventually cleaned up by async DeleteByQuery operations.
 *
 * <p>Correctness of the public API contract (create/update/delete/limit enforcement) is
 * covered by {@link SynonymsManagementAPIServiceIT}.
 */
public class SynonymsGenerationIT extends ESIntegTestCase {

    // Mirror the private constants from SynonymsManagementAPIService for raw-index queries.
    private static final String SYNONYMS_ALIAS = ".synonyms";
    private static final String GENERATION_FIELD = "generation";
    private static final String CURRENT_GENERATION_FIELD = "current_generation";
    private static final String OBJECT_TYPE_FIELD = "type";
    private static final String SYNONYM_RULE_TYPE = "synonym_rule";

    private SynonymsManagementAPIService service;
    private OriginSettingClient originClient;
    private int maxRules;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(ReindexPlugin.class, MapperExtrasPlugin.class);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        maxRules = randomIntBetween(10, 100);
        service = new SynonymsManagementAPIService(client(), maxRules);
        // Use an origin-setting client for raw index queries so system-index access controls pass.
        originClient = new OriginSettingClient(client(), SYNONYMS_ORIGIN);
    }

    /**
     * After a successful create, the set document must have a non-null
     * {@code current_generation} and every rule document must carry the same generation.
     */
    public void testGenerationFieldsWrittenOnCreate() throws Exception {
        String setId = randomIdentifier();
        int numRules = randomIntBetween(1, maxRules);
        SynonymRule[] rules = randomSynonymsSet(numRules, numRules);

        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> future = new PlainActionFuture<>();
        service.putSynonymsSet(setId, rules, false, future);
        assertEquals(SynonymsManagementAPIService.UpdateSynonymsResultStatus.CREATED, future.actionGet().synonymsOperationResult());

        String currentGen = getCurrentGeneration(setId);
        assertNotNull("current_generation must be set after create", currentGen);
        assertEquals("Every rule must carry the current generation", numRules, countRulesWithGeneration(setId, currentGen));
        assertEquals("No rules should belong to any other generation", numRules, countAllRules(setId));
    }

    /**
     * A replace assigns a new unique write token. After update, {@code current_generation}
     * changes and all new rule documents carry the new value.
     */
    public void testGenerationChangesOnReplace() throws Exception {
        String setId = randomIdentifier();
        SynonymRule[] firstRules = randomSynonymsSet(randomIntBetween(1, maxRules / 2));

        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> f1 = new PlainActionFuture<>();
        service.putSynonymsSet(setId, firstRules, false, f1);
        f1.actionGet();
        String firstGen = getCurrentGeneration(setId);
        assertNotNull(firstGen);

        SynonymRule[] secondRules = randomSynonymsSet(randomIntBetween(1, maxRules / 2));

        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> f2 = new PlainActionFuture<>();
        service.putSynonymsSet(setId, secondRules, false, f2);
        assertEquals(SynonymsManagementAPIService.UpdateSynonymsResultStatus.UPDATED, f2.actionGet().synonymsOperationResult());

        String secondGen = getCurrentGeneration(setId);
        assertNotNull(secondGen);
        assertNotEquals("Each replace must produce a distinct generation token", firstGen, secondGen);
        assertEquals("All new rules must carry the replacement generation", secondRules.length, countRulesWithGeneration(setId, secondGen));
    }

    /**
     * {@code getSynonymSetRules} must return only current-generation rules even when
     * stale rules from a previous generation are still physically present in the index.
     *
     * <p>This is achieved by injecting rules with a null generation via the testing
     * bypass {@link SynonymsManagementAPIService#bulkUpdateSynonymsSet}, then performing
     * a proper replace that sets a real current_generation. The stale null-generation
     * rules are then invisible to the read path before async cleanup removes them.
     */
    public void testGetRulesFiltersToCurrentGeneration() throws Exception {
        String setId = randomIdentifier();
        int staleCount = randomIntBetween(2, maxRules / 4);
        int currentCount = randomIntBetween(1, maxRules / 4);

        // Inject "stale" rules with a null generation directly into the index.
        SynonymRule[] staleRules = rulesWithPrefix("stale-", staleCount);
        PlainActionFuture<org.elasticsearch.action.bulk.BulkResponse> bulkFuture = new PlainActionFuture<>();
        service.bulkUpdateSynonymsSet(setId, staleRules, bulkFuture);
        assertFalse("stale rule injection must not fail", bulkFuture.actionGet().hasFailures());

        // Now write the real generation on top. The set document already exists (from
        // bulkUpdateSynonymsSet), so this is an OCC-protected update.
        SynonymRule[] currentRules = rulesWithPrefix("current-", currentCount);
        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> putFuture = new PlainActionFuture<>();
        service.putSynonymsSet(setId, currentRules, false, putFuture);
        putFuture.actionGet();

        // The generation filter must exclude stale rules immediately, before async cleanup.
        PlainActionFuture<PagedResult<SynonymRule>> getFuture = new PlainActionFuture<>();
        service.getSynonymSetRules(setId, getFuture);
        PagedResult<SynonymRule> result = getFuture.actionGet();

        Set<String> expectedIds = Arrays.stream(currentRules).map(SynonymRule::id).collect(Collectors.toSet());
        Set<String> actualIds = Arrays.stream(result.pageResults()).map(SynonymRule::id).collect(Collectors.toSet());
        assertEquals("getSynonymSetRules must return only current-generation rules", expectedIds, actualIds);
        assertEquals(currentCount, result.totalResults());
    }

    /**
     * After a replace, old-generation rule documents are eventually removed from the
     * index by the async stale-generation DeleteByQuery.
     */
    public void testStaleRulesCleanedUpAfterReplace() throws Exception {
        String setId = randomIdentifier();
        SynonymRule[] firstRules = randomSynonymsSet(randomIntBetween(2, maxRules / 2));

        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> f1 = new PlainActionFuture<>();
        service.putSynonymsSet(setId, firstRules, false, f1);
        f1.actionGet();
        final String staleGen = getCurrentGeneration(setId);

        SynonymRule[] secondRules = randomSynonymsSet(randomIntBetween(1, maxRules / 2));
        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> f2 = new PlainActionFuture<>();
        service.putSynonymsSet(setId, secondRules, false, f2);
        f2.actionGet();

        assertBusy(
            () -> assertEquals(
                "Stale rules from generation [" + staleGen + "] must be deleted",
                0,
                countRulesWithGeneration(setId, staleGen)
            ),
            15,
            TimeUnit.SECONDS
        );

        // The physical rule count in the index should converge to the new set size.
        assertBusy(
            () -> assertEquals("Only current-generation rules should remain in the index", secondRules.length, countAllRules(setId)),
            15,
            TimeUnit.SECONDS
        );
    }

    /**
     * Replacing a synonym set with an empty rule array removes all rules.
     * The set document remains (with a new generation), and all old rule documents
     * are eventually cleaned up.
     */
    public void testReplaceWithEmptySetCleanesAllRules() throws Exception {
        String setId = randomIdentifier();
        SynonymRule[] initialRules = randomSynonymsSet(randomIntBetween(2, maxRules / 2));

        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> f1 = new PlainActionFuture<>();
        service.putSynonymsSet(setId, initialRules, false, f1);
        f1.actionGet();

        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> f2 = new PlainActionFuture<>();
        service.putSynonymsSet(setId, new SynonymRule[0], false, f2);
        assertEquals(SynonymsManagementAPIService.UpdateSynonymsResultStatus.UPDATED, f2.actionGet().synonymsOperationResult());

        // The API must report zero rules immediately.
        PlainActionFuture<PagedResult<SynonymRule>> getFuture = new PlainActionFuture<>();
        service.getSynonymSetRules(setId, getFuture);
        assertEquals(0, getFuture.actionGet().totalResults());

        // The physical index must also be empty after async cleanup.
        assertBusy(() -> assertEquals("All rules must be cleaned up after empty replace", 0, countAllRules(setId)), 15, TimeUnit.SECONDS);
    }

    /**
     * After N sequential replaces the index converges to a clean state: every physical
     * rule document belongs to the current generation and no stale documents remain.
     */
    public void testMultipleSequentialReplacesEndInCleanState() throws Exception {
        String setId = randomIdentifier();
        int numReplaces = randomIntBetween(3, 6);

        for (int i = 0; i < numReplaces; i++) {
            SynonymRule[] rules = randomSynonymsSet(randomIntBetween(1, maxRules / numReplaces + 1));
            PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> f = new PlainActionFuture<>();
            service.putSynonymsSet(setId, rules, false, f);
            f.actionGet();
        }

        assertBusy(() -> {
            String currentGen = getCurrentGeneration(setId);
            assertNotNull("current_generation must be present after all replaces", currentGen);
            long currentCount = countRulesWithGeneration(setId, currentGen);
            long totalCount = countAllRules(setId);
            assertEquals("After async cleanup, only current-generation rules should remain in the index", currentCount, totalCount);
        }, 30, TimeUnit.SECONDS);
    }

    /**
     * Two concurrent creates for the same set race on an OCC CREATE. After both
     * complete (one may fail), the set holds a single complete, consistent batch of
     * rules — no mixing between the two writers' rule sets.
     *
     * <p>Non-overlapping rule ID prefixes ("writer-a-" vs "writer-b-") make it
     * unambiguous which writer's batch is the current one.
     */
    public void testConcurrentCreatesProduceConsistentState() throws Exception {
        String setId = randomIdentifier();
        int numRules = randomIntBetween(2, maxRules / 4);

        SynonymRule[] rulesA = rulesWithPrefix("writer-a-", numRules);
        SynonymRule[] rulesB = rulesWithPrefix("writer-b-", numRules);

        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> futureA = new PlainActionFuture<>();
        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> futureB = new PlainActionFuture<>();

        // Fire both writes without waiting, so they can race.
        service.putSynonymsSet(setId, rulesA, false, futureA);
        service.putSynonymsSet(setId, rulesB, false, futureB);

        boolean aSucceeded = false;
        boolean bSucceeded = false;
        try {
            futureA.actionGet();
            aSucceeded = true;
        } catch (Exception ignored) {}
        try {
            futureB.actionGet();
            bSucceeded = true;
        } catch (Exception ignored) {}

        assertTrue("At least one concurrent create must succeed", aSucceeded || bSucceeded);

        // After stale/orphan cleanup, exactly one writer's complete batch must be present.
        final Set<String> idsA = ruleIds(rulesA);
        final Set<String> idsB = ruleIds(rulesB);

        assertBusy(() -> {
            PlainActionFuture<PagedResult<SynonymRule>> getFuture = new PlainActionFuture<>();
            service.getSynonymSetRules(setId, getFuture);
            Set<String> actualIds = ruleIds(getFuture.actionGet().pageResults());

            assertTrue(
                "Set must contain exactly one complete writer batch, got: " + actualIds,
                idsA.equals(actualIds) || idsB.equals(actualIds)
            );
        }, 15, TimeUnit.SECONDS);

        // All physical rule documents should converge to a single complete batch.
        assertBusy(
            () -> assertEquals("Only one writer's rules should remain in the physical index", numRules, countAllRules(setId)),
            15,
            TimeUnit.SECONDS
        );
    }

    /**
     * Two concurrent updates to an existing set race on an OCC flip. After both
     * complete (one may fail), the set holds a single consistent batch and all
     * stale or orphaned rules are eventually cleaned up.
     *
     * <p>Non-overlapping rule ID prefixes prevent ambiguity about which writer won.
     * The initial set uses a distinct prefix so it too can be identified if both
     * updates fail (an extremely unlikely edge case).
     */
    public void testConcurrentUpdatesProduceConsistentState() throws Exception {
        String setId = randomIdentifier();

        // Create a stable initial state.
        SynonymRule[] initialRules = rulesWithPrefix("initial-", randomIntBetween(1, maxRules / 4));
        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> initFuture = new PlainActionFuture<>();
        service.putSynonymsSet(setId, initialRules, false, initFuture);
        assertEquals(SynonymsManagementAPIService.UpdateSynonymsResultStatus.CREATED, initFuture.actionGet().synonymsOperationResult());

        int numRules = randomIntBetween(2, maxRules / 4);
        SynonymRule[] rulesA = rulesWithPrefix("writer-a-", numRules);
        SynonymRule[] rulesB = rulesWithPrefix("writer-b-", numRules);

        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> futureA = new PlainActionFuture<>();
        PlainActionFuture<SynonymsManagementAPIService.SynonymsReloadResult> futureB = new PlainActionFuture<>();

        service.putSynonymsSet(setId, rulesA, false, futureA);
        service.putSynonymsSet(setId, rulesB, false, futureB);

        boolean aSucceeded = false;
        boolean bSucceeded = false;
        try {
            futureA.actionGet();
            aSucceeded = true;
        } catch (Exception ignored) {}
        try {
            futureB.actionGet();
            bSucceeded = true;
        } catch (Exception ignored) {}

        assertTrue("At least one concurrent update must succeed", aSucceeded || bSucceeded);

        final Set<String> idsA = ruleIds(rulesA);
        final Set<String> idsB = ruleIds(rulesB);
        final Set<String> idsInitial = ruleIds(initialRules);

        assertBusy(() -> {
            PlainActionFuture<PagedResult<SynonymRule>> getFuture = new PlainActionFuture<>();
            service.getSynonymSetRules(setId, getFuture);
            Set<String> actualIds = ruleIds(getFuture.actionGet().pageResults());

            assertTrue(
                "Set must contain exactly one complete writer batch, got: " + actualIds,
                idsA.equals(actualIds) || idsB.equals(actualIds) || idsInitial.equals(actualIds)
            );
        }, 15, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds rules with known IDs of the form {@code prefix + index}. */
    private SynonymRule[] rulesWithPrefix(String prefix, int count) {
        SynonymRule[] rules = new SynonymRule[count];
        for (int i = 0; i < count; i++) {
            rules[i] = new SynonymRule(prefix + i, prefix + i + ", " + randomAlphaOfLengthBetween(3, 8));
        }
        return rules;
    }

    private Set<String> ruleIds(SynonymRule[] rules) {
        return Arrays.stream(rules).map(SynonymRule::id).collect(Collectors.toSet());
    }

    private Set<String> ruleIds(PagedResult<SynonymRule> result) {
        return ruleIds(result.pageResults());
    }

    /**
     * Reads {@code current_generation} directly from the set document in the synonyms index.
     * Returns {@code null} if the document does not exist.
     */
    private String getCurrentGeneration(String setId) {
        var response = originClient.prepareGet(SYNONYMS_ALIAS, setId).get();
        if (response.isExists() == false) {
            return null;
        }
        return (String) response.getSourceAsMap().get(CURRENT_GENERATION_FIELD);
    }

    /**
     * Counts all rule documents for {@code setId} whose {@code generation} field
     * matches exactly {@code generation}.
     *
     * <p>{@link org.elasticsearch.action.search.SearchResponse} is ref-counted;
     * this method releases it before returning.
     */
    private long countRulesWithGeneration(String setId, String generation) {
        var response = originClient.prepareSearch(SYNONYMS_ALIAS)
            .setQuery(
                QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery(SYNONYMS_SET_FIELD, setId))
                    .filter(QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_RULE_TYPE))
                    .filter(QueryBuilders.termQuery(GENERATION_FIELD, generation))
            )
            .setSize(0)
            .setTrackTotalHits(true)
            .get();
        try {
            return response.getHits().getTotalHits().value();
        } finally {
            response.decRef();
        }
    }

    /**
     * Counts all rule documents for {@code setId}, regardless of generation.
     * Used to verify that async cleanup has converged.
     *
     * <p>{@link org.elasticsearch.action.search.SearchResponse} is ref-counted;
     * this method releases it before returning.
     */
    private long countAllRules(String setId) {
        var response = originClient.prepareSearch(SYNONYMS_ALIAS)
            .setQuery(
                QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery(SYNONYMS_SET_FIELD, setId))
                    .filter(QueryBuilders.termQuery(OBJECT_TYPE_FIELD, SYNONYM_RULE_TYPE))
            )
            .setSize(0)
            .setTrackTotalHits(true)
            .get();
        try {
            return response.getHits().getTotalHits().value();
        } finally {
            response.decRef();
        }
    }
}
