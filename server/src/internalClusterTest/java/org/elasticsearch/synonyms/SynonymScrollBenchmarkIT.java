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
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.mapper.extras.MapperExtrasPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.reindex.ReindexPlugin;
import org.elasticsearch.test.ESIntegTestCase;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.test.ESTestCase.randomAlphaOfLengthBetween;

/**
 * DO NOT MERGE THIS TEST INTO MAIN. This is for manual testing only.
 * <p>
 * Exploratory benchmarks for scroll-based synonym set retrieval.
 * Measures how put and scroll-read performance scale with rule count and batch size.
 * Not intended for CI — run manually with {@code -Dtests.nightly=true}.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 1, numClientNodes = 0, supportsDedicatedMasters = false)
// @AwaitsFix(bugUrl = "manual benchmark — not for CI")
public class SynonymScrollBenchmarkIT extends ESIntegTestCase {

    private static final int START_RULES = 100_000;
    private static final int STEP = 100_000;
    private static final int MAX_RULES = 1_000_000;
    private static final int PUT_TIMEOUT_SECONDS = 120;
    private static final int GET_TIMEOUT_SECONDS = 120;
    private static final int SCROLL_TRIALS = 20;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(ReindexPlugin.class, MapperExtrasPlugin.class);
    }

    public void testScrollDefaultBatchSize() throws Exception {
        runBenchmark("default batch (10000)", SynonymsManagementAPIService.SCROLL_BATCH_SIZE);
    }

    public void testScrollSmallBatch() throws Exception {
        runBenchmark("small batch (500)", 500);
    }

    public void testScrollLargeBatch() throws Exception {
        runBenchmark("large batch (25000)", 100_000);
    }

    public void testScrollTinyBatch() throws Exception {
        runBenchmark("tiny batch (100)", 100, 100, 100, 5_000);
    }

    private void runBenchmark(String label, int scrollBatchSize) throws Exception {
        runBenchmark(label, scrollBatchSize, START_RULES, STEP, MAX_RULES);
    }

    private void runBenchmark(String label, int scrollBatchSize, int startRules, int step, int maxRules) throws Exception {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        suppressNoisyLoggers();

        logger.info(
            "=== Scroll Benchmark: {} (start={}, step={}, max={}, batchSize={}) ===",
            label,
            startRules,
            step,
            maxRules,
            scrollBatchSize
        );
        logHeader();

        int lastGood = 0;

        for (int ruleCount = startRules; ruleCount <= maxRules; ruleCount += step) {
            try {
                indicesAdmin().prepareDelete(".synonyms").get();
            } catch (Exception e) {
                // index may not exist on first iteration
            }

            String synonymSetId = "bench_" + ruleCount;
            SynonymsManagementAPIService service = new SynonymsManagementAPIService(client(), ruleCount + 1, scrollBatchSize);

            SynonymRule[] rules = uniqueSynonymsSet(ruleCount);

            long putStart = System.nanoTime();
            boolean putOk = doPut(service, synonymSetId, rules);
            long putMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - putStart);

            if (putOk == false) {
                logger.info("=== Put failed at {} rules — stopping ===", ruleCount);
                return;
            }

            ensureGreen();

            long[] scrollTimes = new long[SCROLL_TRIALS];
            long[] heapDeltas = new long[SCROLL_TRIALS];
            String failStatus = null;

            for (int trial = 0; trial < SCROLL_TRIALS; trial++) {
                System.gc();
                long heapBefore = memoryBean.getHeapMemoryUsage().getUsed();

                long scrollStart = System.nanoTime();
                ScrollResult scrollResult = doScroll(service, synonymSetId, ruleCount);
                long scrollMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scrollStart);

                System.gc();
                long heapAfter = memoryBean.getHeapMemoryUsage().getUsed();

                scrollTimes[trial] = scrollMs;
                heapDeltas[trial] = (heapAfter - heapBefore) / (1024 * 1024);

                if (scrollResult.error != null) {
                    failStatus = "SCROLL_FAIL: " + scrollResult.error;
                    break;
                } else if (scrollResult.retrieved != ruleCount) {
                    failStatus = "MISMATCH: expected " + ruleCount + " got " + scrollResult.retrieved;
                    break;
                }
            }

            int completedTrials = failStatus != null ? 1 : SCROLL_TRIALS;
            double meanScroll = mean(scrollTimes, completedTrials);
            double stddevScroll = stddev(scrollTimes, completedTrials, meanScroll);
            double meanHeapDelta = mean(heapDeltas, completedTrials);
            long heapAfterMb = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);

            String status;
            boolean failed;
            if (failStatus != null) {
                status = failStatus;
                failed = true;
            } else if (meanScroll > 30_000) {
                status = "SLOW (>30s)";
                failed = true;
            } else {
                status = "OK";
                failed = false;
            }

            int scrollIterations = (ruleCount + scrollBatchSize - 1) / scrollBatchSize;

            logResult(ruleCount, putMs, meanScroll, stddevScroll, scrollIterations, meanHeapDelta, heapAfterMb, status);

            if (failed) {
                logger.info("=== Benchmark Complete: {} — failed at {} rules, last OK at {} ===", label, ruleCount, lastGood);
                return;
            }
            lastGood = ruleCount;
        }

        logger.info("=== Benchmark Complete: {} — no failure up to {} rules ===", label, maxRules);
    }

    private record ScrollResult(int retrieved, String error) {}

    private boolean doPut(SynonymsManagementAPIService service, String synonymSetId, SynonymRule[] rules) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        service.putSynonymsSet(synonymSetId, rules, false, new ActionListener<>() {
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

    private ScrollResult doScroll(SynonymsManagementAPIService service, String synonymSetId, int expectedCount) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> retrieved = new AtomicReference<>(0);
        AtomicReference<String> error = new AtomicReference<>();

        try {
            assertBusy(() -> {
                service.getSynonymSetRules(synonymSetId, new ActionListener<>() {
                    @Override
                    public void onResponse(PagedResult<SynonymRule> result) {
                        retrieved.set(result.pageResults().length);
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(Exception e) {
                        error.set(rootCause(e));
                        latch.countDown();
                    }
                });
            }, GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            latch.await(GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ScrollResult(0, rootCause(e));
        }

        if (error.get() != null) {
            return new ScrollResult(0, error.get());
        }
        return new ScrollResult(retrieved.get(), null);
    }

    private void logHeader() {
        logger.info(
            "Rules    | Put (ms) | Scroll Mean (ms) | Scroll StdDev (ms) | Iterations | Heap Delta (MB) | Heap After (MB) | Status"
        );
        logger.info(
            "---------|----------|------------------|--------------------|------------|-----------------|-----------------|-------"
        );
    }

    private void logResult(
        int rules,
        long putMs,
        double scrollMean,
        double scrollStddev,
        int iterations,
        double meanHeapDelta,
        long heapAfterMb,
        String status
    ) {
        logger.info(
            "{} | {} | {} | {} | {} | {} | {} | {}",
            rules,
            putMs,
            String.format(java.util.Locale.US, "%.1f", scrollMean),
            String.format(java.util.Locale.US, "%.1f", scrollStddev),
            iterations,
            String.format(java.util.Locale.US, "%.1f", meanHeapDelta),
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

    private static double stddev(long[] values, int count, double mean) {
        if (count < 2) return 0;
        double sumSq = 0;
        for (int i = 0; i < count; i++) {
            double diff = values[i] - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (count - 1));
    }

    private static SynonymRule[] uniqueSynonymsSet(int count) {
        SynonymRule[] rules = new SynonymRule[count];
        for (int i = 0; i < count; i++) {
            String id = "rule_" + i;
            int termCount = between(1, 10);
            String[] terms = new String[termCount];
            for (int t = 0; t < termCount; t++) {
                terms[t] = randomAlphaOfLengthBetween(1, 10);
            }
            rules[i] = new SynonymRule(id, String.join(", ", terms));
        }
        return rules;
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
