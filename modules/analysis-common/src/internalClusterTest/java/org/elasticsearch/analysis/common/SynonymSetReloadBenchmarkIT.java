/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.analyze.ReloadAnalyzersRequest;
import org.elasticsearch.action.admin.indices.analyze.ReloadAnalyzersResponse;
import org.elasticsearch.action.admin.indices.analyze.TransportReloadAnalyzersAction;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.mapper.extras.MapperExtrasPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.reindex.ReindexPlugin;
import org.elasticsearch.synonyms.SynonymRule;
import org.elasticsearch.synonyms.SynonymsManagementAPIService;
import org.elasticsearch.test.ESIntegTestCase;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.carrotsearch.randomizedtesting.annotations.TimeoutSuite;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

/**
 * DO NOT MERGE THIS TEST INTO MAIN. This is for manual testing only.
 * <p>
 * Full integration benchmark: PUT synonym rules to .synonyms index,
 * then reload analyzer (scroll + FST build). Measures the complete path.
 * Not intended for CI — run manually with {@code -Dtests.nightly=true}.
 */
@TimeoutSuite(millis = 60 * 60 * 1000)
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 1, numClientNodes = 0, supportsDedicatedMasters = false)
public class SynonymSetReloadBenchmarkIT extends ESIntegTestCase {

    private static final String INDEX_NAME = "synonym_benchmark_index";
    private static final String SYNONYM_SET_ID = "bench_set";

    private static final int START_RULES = 100_000;
    private static final int STEP = 100_000;
    private static final int MAX_RULES = 1_000_000;
    private static final int PUT_TIMEOUT_SECONDS = 120;
    private static final int RELOAD_TRIALS = 20;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(CommonAnalysisPlugin.class, ReindexPlugin.class, MapperExtrasPlugin.class);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put(SynonymTokenFilterFactory.MAX_SYNONYM_SET_TOKENS_SETTING.getKey(), Integer.MAX_VALUE)
            .build();
    }

    public void testFullReloadRandomShort() throws Exception {
        runBenchmark("random short (3-6 chars)", () -> randomAlphaOfLengthBetween(3, 6));
    }

    public void testFullReloadRandomMixed() throws Exception {
        runBenchmark("random mixed (3-25 chars)", () -> randomAlphaOfLengthBetween(3, 25));
    }

    @FunctionalInterface
    private interface TokenGenerator {
        String generate();
    }

    private void runBenchmark(String label, TokenGenerator tokenGenerator) throws Exception {
        suppressNoisyLoggers();

        assertAcked(
            indicesAdmin().prepareCreate(INDEX_NAME)
                .setSettings(
                    indexSettings(1, 0).put("analysis.analyzer.bench_analyzer.tokenizer", "standard")
                        .put("analysis.analyzer.bench_analyzer.filter", "bench_synonym_filter")
                        .put("analysis.filter.bench_synonym_filter.type", "synonym_graph")
                        .put("analysis.filter.bench_synonym_filter.synonyms_set", SYNONYM_SET_ID)
                        .put("analysis.filter.bench_synonym_filter.updateable", "true")
                        .put("analysis.filter.bench_synonym_filter.lenient", "true")
                )
                .setMapping("field", "type=text,analyzer=standard,search_analyzer=bench_analyzer")
        );
        ensureGreen(INDEX_NAME);

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        logger.info("=== Full Reload Benchmark: {} (start={}, step={}, max={}) ===", label, START_RULES, STEP, MAX_RULES);
        logHeader();

        int lastGood = 0;

        for (int ruleCount = START_RULES; ruleCount <= MAX_RULES; ruleCount += STEP) {
            try {
                indicesAdmin().prepareDelete(".synonyms").get();
            } catch (Exception e) {
                // may not exist on first iteration
            }

            SynonymsManagementAPIService service = new SynonymsManagementAPIService(client(), ruleCount + 1);
            SynonymRule[] rules = generateRules(ruleCount, tokenGenerator);

            long putStart = System.nanoTime();
            boolean putOk = doPut(service, rules);
            long putMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - putStart);

            if (putOk == false) {
                logger.info("=== Put failed at {} rules — stopping ===", ruleCount);
                return;
            }

            ensureGreen();

            long[] reloadTimes = new long[RELOAD_TRIALS];
            String failError = null;

            for (int trial = 0; trial < RELOAD_TRIALS; trial++) {
                System.gc();

                long reloadStart = System.nanoTime();
                ReloadResult reloadResult = tryReload();
                long reloadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reloadStart);

                reloadTimes[trial] = reloadMs;

                if (reloadResult.error != null) {
                    failError = reloadResult.error;
                    break;
                }
            }

            int completedTrials = failError != null ? 1 : RELOAD_TRIALS;
            double meanReload = mean(reloadTimes, completedTrials);
            double medianReload = median(reloadTimes, completedTrials);
            double stddevReload = stddev(reloadTimes, completedTrials, meanReload);

            System.gc();
            long heapAfterMb = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);

            String status;
            boolean failed;
            if (failError != null) {
                status = "RELOAD_FAIL: " + failError;
                failed = true;
            } else if (meanReload > 120_000) {
                status = "SLOW (>120s)";
                failed = true;
            } else {
                status = "OK";
                failed = false;
            }

            logResult(ruleCount, putMs, meanReload, medianReload, stddevReload, heapAfterMb, status);

            if (failed) {
                logger.info("=== Benchmark Complete: {} — failed at {} rules, last OK at {} ===", label, ruleCount, lastGood);
                return;
            }
            lastGood = ruleCount;
        }

        logger.info("=== Benchmark Complete: {} — no failure up to {} rules ===", label, MAX_RULES);
    }

    private record ReloadResult(String error) {}

    private ReloadResult tryReload() {
        try {
            ReloadAnalyzersResponse response = client().execute(
                TransportReloadAnalyzersAction.TYPE,
                new ReloadAnalyzersRequest(null, false, INDEX_NAME)
            ).actionGet(TimeValue.timeValueSeconds(120));

            if (response.getFailedShards() > 0) {
                Throwable cause = response.getShardFailures()[0].getCause();
                return new ReloadResult(rootCause(cause));
            }
            return new ReloadResult(null);
        } catch (Exception e) {
            return new ReloadResult(rootCause(e));
        }
    }

    private boolean doPut(SynonymsManagementAPIService service, SynonymRule[] rules) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        service.putSynonymsSet(SYNONYM_SET_ID, rules, false, new ActionListener<>() {
            @Override
            public void onResponse(SynonymsManagementAPIService.SynonymsReloadResult result) {
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                error.set(e);
                latch.countDown();
            }
        });

        boolean completed = latch.await(PUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (completed == false) {
            logger.error("Put timed out after {}s for {} rules", PUT_TIMEOUT_SECONDS, rules.length);
            return false;
        }
        if (error.get() != null) {
            logger.error("Put failed for {} rules: {}", rules.length, error.get().getMessage());
            return false;
        }
        return true;
    }

    private static SynonymRule[] generateRules(int count, TokenGenerator tokenGenerator) {
        SynonymRule[] rules = new SynonymRule[count];
        for (int i = 0; i < count; i++) {
            int termCount = between(1, 10);
            String[] terms = new String[termCount];
            for (int t = 0; t < termCount; t++) {
                terms[t] = tokenGenerator.generate();
            }
            rules[i] = new SynonymRule("rule_" + i, String.join(", ", terms));
        }
        return rules;
    }

    private void logHeader() {
        logger.info("Rules    | Put (ms) | Reload Mean (ms) | Reload Median (ms) | Reload StdDev (ms) | Heap After (MB) | Status");
        logger.info("---------|----------|------------------|--------------------|--------------------|-----------------|-------");
    }

    private void logResult(int rules, long putMs, double reloadMean, double reloadMedian, double reloadStddev, long heapAfterMb,
                           String status) {
        logger.info(
            "{} | {} | {} | {} | {} | {} | {}",
            rules,
            putMs,
            String.format(java.util.Locale.US, "%.1f", reloadMean),
            String.format(java.util.Locale.US, "%.1f", reloadMedian),
            String.format(java.util.Locale.US, "%.1f", reloadStddev),
            heapAfterMb,
            status
        );
    }

    private static double mean(long[] values, int count) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += values[i];
        }
        return (double) sum / count;
    }

    private static double median(long[] values, int count) {
        long[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        if (count % 2 == 0) {
            return (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0;
        }
        return sorted[count / 2];
    }

    private static double stddev(long[] values, int count, double mean) {
        if (count < 2) return 0;
        double sumSq = 0;
        for (int i = 0; i < count; i++) {
            double diff = values[i] - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (count - 1));
    }

    private static void suppressNoisyLoggers() {
        setLogLevel("org.elasticsearch.cluster", Level.WARN);
        setLogLevel("org.elasticsearch.monitor.jvm.JvmGcMonitorService", Level.ERROR);
        setLogLevel("org.elasticsearch.common.util.FeatureFlag", Level.ERROR);
        setLogLevel("org.elasticsearch.indices.recovery.RecoverySettings", Level.WARN);
        setLogLevel("org.elasticsearch.transport.TransportService", Level.WARN);
        setLogLevel("org.elasticsearch.node.Node", Level.WARN);
        setLogLevel("org.elasticsearch.env", Level.WARN);
        setLogLevel("org.elasticsearch.gateway", Level.WARN);
        setLogLevel("org.elasticsearch.discovery", Level.WARN);
        setLogLevel("org.elasticsearch.transport.netty4", Level.WARN);
        setLogLevel("org.elasticsearch.reservedstate", Level.WARN);
        setLogLevel("org.elasticsearch.health", Level.WARN);
        setLogLevel("org.elasticsearch.index.mapper.extras", Level.WARN);
        setLogLevel("org.elasticsearch.test", Level.WARN);
        setLogLevel("org.elasticsearch.deprecation", Level.ERROR);
        setLogLevel("org.elasticsearch.action.admin.indices.analyze.TransportReloadAnalyzersAction", Level.WARN);
    }

    private static void setLogLevel(String loggerName, Level level) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig(loggerName);
        if (loggerConfig.getName().equals(loggerName) == false) {
            loggerConfig = new LoggerConfig(loggerName, level, true);
            config.addLogger(loggerName, loggerConfig);
        } else {
            loggerConfig.setLevel(level);
        }
        ctx.updateLoggers();
    }

    private static String rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
