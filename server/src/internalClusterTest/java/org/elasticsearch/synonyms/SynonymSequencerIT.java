/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.synonyms;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.synonyms.PutSynonymsAction;
import org.elasticsearch.action.synonyms.SynonymUpdateResponse;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.index.mapper.extras.MapperExtrasPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.reindex.ReindexPlugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.action.synonyms.SynonymsTestUtils.randomSynonymsSet;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.empty;

/**
 * Integration tests for {@link SynonymSequencer}. Verifies that concurrent synonym write requests
 * are serialized through the master node queue and do not produce version conflict errors or
 * mixed state.
 */
public class SynonymSequencerIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(ReindexPlugin.class, MapperExtrasPlugin.class);
    }

    public void testConcurrentPutsProduceNoVersionConflicts() throws Exception {
        int concurrency = 10;
        String synonymsSetId = "concurrent-test-set";
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        List<Exception> failures = new CopyOnWriteArrayList<>();
        List<PlainActionFuture<SynonymUpdateResponse>> futures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            PlainActionFuture<SynonymUpdateResponse> future = new PlainActionFuture<>();
            futures.add(future);
            final SynonymRule[] rules = randomSynonymsSet(1, 3);
            threads.add(new Thread(() -> {
                try {
                    barrier.await(); // release all threads simultaneously
                    PutSynonymsAction.Request request = new PutSynonymsAction.Request(
                        synonymsSetId,
                        false,
                        buildSynonymSetBody(rules),
                        XContentType.JSON
                    );
                    client().execute(PutSynonymsAction.INSTANCE, request, ActionListener.wrap(future::onResponse, future::onFailure));
                } catch (Exception e) {
                    failures.add(e);
                    future.onFailure(e);
                }
            }));
        }

        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }

        assertThat("no failures before dispatch", failures, empty());

        // Wait for all futures — any VersionConflictEngineException surfaces here
        List<Exception> actionFailures = new CopyOnWriteArrayList<>();
        for (PlainActionFuture<SynonymUpdateResponse> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                actionFailures.add(e);
            }
        }
        assertThat("concurrent synonym writes must not produce version conflicts or other failures", actionFailures, empty());
    }

    private static BytesReference buildSynonymSetBody(SynonymRule[] rules) throws Exception {
        try (XContentBuilder builder = jsonBuilder()) {
            builder.startObject();
            builder.startArray(SynonymsManagementAPIService.SYNONYMS_SET_FIELD);
            for (SynonymRule rule : rules) {
                rule.toXContent(builder, null);
            }
            builder.endArray();
            builder.endObject();
            return BytesReference.bytes(builder);
        }
    }
}
