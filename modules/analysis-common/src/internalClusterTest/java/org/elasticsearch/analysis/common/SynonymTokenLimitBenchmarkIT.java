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
import org.elasticsearch.action.admin.indices.analyze.ReloadAnalyzersRequest;
import org.elasticsearch.action.admin.indices.analyze.ReloadAnalyzersResponse;
import org.elasticsearch.action.admin.indices.analyze.TransportReloadAnalyzersAction;
import org.elasticsearch.core.PathUtils;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

/**
 * DO NOT MERGE THIS TEST INTO MAIN. This is for manual testing only.
 * <p>
 * Exploratory benchmarks to find OOM and timeout thresholds for synonym analyzer builds.
 * Not intended for CI — run manually with {@code -Dtests.nightly=true}.
 *
 * <p>Each test writes progressively larger synonym files using a specific token generation
 * strategy and reloads the analyzer via the cluster, incrementing by {@link #STEP} tokens
 * until failure or {@link #MAX_TOKENS} is reached. Total character count is tracked alongside
 * token count to determine which metric better predicts FST memory exhaustion.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 1, numClientNodes = 0, supportsDedicatedMasters = false)
// @AwaitsFix(bugUrl = "manual benchmark — not for CI")
public class SynonymTokenLimitBenchmarkIT extends ESIntegTestCase {

    private static final String INDEX_NAME = "synonym_benchmark_index";
    private static final String SYNONYMS_FILE = "benchmark_synonyms.txt";

    private static final int START_TOKENS = 100_000;
    private static final int STEP = 100_000;
    private static final int MAX_TOKENS = 10_000_000;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(CommonAnalysisPlugin.class);
    }

    // ---- Random token tests ----

    public void testClusterReloadRandomShort() throws Exception {
        runBenchmark("random alpha short (3-6 chars)", i -> randomAlphaOfLengthBetween(3, 6));
    }

    public void testClusterReloadRandomLong() throws Exception {
        runBenchmark("random alpha long (10-25 chars)", i -> randomAlphaOfLengthBetween(10, 25));
    }

    public void testClusterReloadRandomMixed() throws Exception {
        runBenchmark("random alpha mixed (3-25 chars)", i -> randomAlphaOfLengthBetween(3, 25));
    }

    public void testClusterReloadRandomExtraLong() throws Exception {
        runBenchmark("random alpha extra-long (100-255 chars)", i -> randomAlphaOfLengthBetween(100, 255));
    }

    // ---- Unicode token tests ----

    public void testClusterReloadRealisticUnicodeShort() throws Exception {
        runBenchmark("realistic unicode short (3-6 chars)", 1_000, 1_000, i -> randomRealisticUnicodeOfLengthBetween(1, 2));
    }

    // ---- Real Chinese token tests ----

    public void testClusterReloadChineseTokens() throws Exception {
        String[] tokens = loadChineseTokens();
        runBenchmark(
            "Chinese compound tokens (" + tokens.length + " base tokens)",
            i -> tokens[i % tokens.length] + tokens[(i / tokens.length) % tokens.length]
        );
    }

    private static String[] loadChineseTokens() throws IOException {
        Path path = PathUtils.get(
            System.getProperty("user.home"),
            "Intellij/tika-main/tika-eval/tika-eval-core/src/main/resources/common_tokens/zho"
        );
        List<String> tokens = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String token = line.split("\t")[0];
            if (token.isEmpty() == false) {
                tokens.add(token);
            }
        }
        return tokens.toArray(String[]::new);
    }

    // ---- Prefix token tests ----

    public void testClusterReloadPrefixShort() throws Exception {
        runBenchmark("prefix short (syn_N)", i -> "syn_" + i);
    }

    public void testClusterReloadPrefixLong() throws Exception {
        runBenchmark("prefix long (synonym_token_N)", i -> "synonym_token_" + i);
    }

    public void testClusterReloadPrefixMulti() throws Exception {
        String[] prefixes = { "color_", "size_", "shape_" };
        runBenchmark("prefix multi (color_/size_/shape_ N)", i -> prefixes[i % prefixes.length] + i);
    }

    // ---- Core benchmark logic ----

    private void runBenchmark(String label, TokenGenerator tokenGenerator) throws Exception {
        runBenchmark(label, START_TOKENS, STEP, tokenGenerator);
    }

    private void runBenchmark(String label, int startTokens, int step, TokenGenerator tokenGenerator) throws Exception {
        Path config = internalCluster().getInstance(Environment.class).configDir();
        Path synonymsFile = config.resolve(SYNONYMS_FILE);

        writeSynonymFile(synonymsFile, 1, tokenGenerator);

        assertAcked(
            indicesAdmin().prepareCreate(INDEX_NAME)
                .setSettings(
                    indexSettings(1, 0).put("analysis.analyzer.bench_analyzer.tokenizer", "standard")
                        .put("analysis.analyzer.bench_analyzer.filter", "bench_synonym_filter")
                        .put("analysis.filter.bench_synonym_filter.type", "synonym_graph")
                        .put("analysis.filter.bench_synonym_filter.synonyms_path", SYNONYMS_FILE)
                        .put("analysis.filter.bench_synonym_filter.updateable", "true")
                        .put("analysis.filter.bench_synonym_filter.lenient", "true")
                )
                .setMapping("field", "type=text,analyzer=standard,search_analyzer=bench_analyzer")
        );
        ensureGreen(INDEX_NAME);
        suppressNoisyLoggers();

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        logger.info("=== Cluster Reload Benchmark: {} (start={}, step={}) ===", label, startTokens, step);
        logHeader();

        int lastGood = 0;
        long lastGoodChars = 0;

        for (int tokenCount = startTokens; tokenCount <= MAX_TOKENS; tokenCount += step) {
            long writeStart = System.nanoTime();
            long totalChars = writeSynonymFile(synonymsFile, tokenCount, tokenGenerator);
            long writeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStart);
            ReloadResult result = tryReload(synonymsFile, writeMs, memoryBean);
            logResult(tokenCount, totalChars, result);

            if (result.failed) {
                logger.info(
                    "=== Benchmark Complete: {} — failed at {} tokens ({} chars), last OK at {} tokens ({} chars) ===",
                    label,
                    tokenCount,
                    totalChars,
                    lastGood,
                    lastGoodChars
                );
                return;
            }
            lastGood = tokenCount;
            lastGoodChars = totalChars;
        }

        logger.info("=== Benchmark Complete: {} — no failure up to {} tokens ===", label, MAX_TOKENS);
    }

    private record ReloadResult(
        long writeMs,
        long reloadMs,
        long heapUsedMb,
        long peakHeapMb,
        long heapDeltaMb,
        String status,
        boolean failed
    ) {}

    private ReloadResult tryReload(Path synonymsFile, long writeMs, MemoryMXBean memoryBean) {
        System.gc();
        long heapBefore = memoryBean.getHeapMemoryUsage().getUsed();

        PeakHeapSampler sampler = new PeakHeapSampler(memoryBean);
        sampler.start();

        long reloadStart = System.nanoTime();
        try {
            ReloadAnalyzersResponse reloadResponse = client().execute(
                TransportReloadAnalyzersAction.TYPE,
                new ReloadAnalyzersRequest(null, false, INDEX_NAME)
            ).actionGet(TimeValue.timeValueSeconds(120));

            long reloadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reloadStart);
            sampler.stop();

            System.gc();
            long heapAfter = memoryBean.getHeapMemoryUsage().getUsed();
            long heapDeltaMb = Math.max(0, (heapAfter - heapBefore)) / (1024 * 1024);
            long heapUsedMb = heapAfter / (1024 * 1024);
            long peakHeapMb = sampler.getPeakBytes() / (1024 * 1024);

            if (reloadResponse.getFailedShards() > 0) {
                Throwable cause = reloadResponse.getShardFailures()[0].getCause();
                return new ReloadResult(writeMs, reloadMs, heapUsedMb, peakHeapMb, heapDeltaMb, "SHARD_FAIL: " + rootCauseMessage(cause), true);
            }

            String status = reloadMs > 30_000 ? "SLOW (>30s)" : "OK";
            boolean failed = reloadMs > 30_000;
            return new ReloadResult(writeMs, reloadMs, heapUsedMb, peakHeapMb, heapDeltaMb, status, failed);
        } catch (Exception e) {
            long reloadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reloadStart);
            sampler.stop();
            long heapUsedMb = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
            long peakHeapMb = sampler.getPeakBytes() / (1024 * 1024);
            return new ReloadResult(writeMs, reloadMs, heapUsedMb, peakHeapMb, -1, rootCauseMessage(e), true);
        }
    }

    private static class PeakHeapSampler {
        private final MemoryMXBean memoryBean;
        private volatile long peakBytes;
        private volatile boolean running;
        private Thread thread;

        PeakHeapSampler(MemoryMXBean memoryBean) {
            this.memoryBean = memoryBean;
            this.peakBytes = memoryBean.getHeapMemoryUsage().getUsed();
        }

        void start() {
            running = true;
            thread = new Thread(() -> {
                while (running) {
                    long used = memoryBean.getHeapMemoryUsage().getUsed();
                    if (used > peakBytes) {
                        peakBytes = used;
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "peak-heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long getPeakBytes() {
            return peakBytes;
        }
    }

    private void logHeader() {
        logger.info("Tokens   | Tot Chars  | Write (ms) | Reload (ms) | Tokens/ms | Heap Used | Peak Heap | Heap Delta | Status");
        logger.info("---------|------------|------------|-------------|-----------|-----------|-----------|------------|-------");
    }

    private void logResult(int tokenCount, long totalChars, ReloadResult r) {
        long tokensPerMs = r.reloadMs > 0 ? tokenCount / r.reloadMs : 0;
        String heapDelta = r.heapDeltaMb >= 0 ? r.heapDeltaMb + " MB" : "?";
        logger.info(
            "{} | {} | {} | {} | {} | {} MB | {} MB | {} | {}",
            tokenCount,
            totalChars,
            r.writeMs,
            r.reloadMs,
            tokensPerMs,
            r.heapUsedMb,
            r.peakHeapMb,
            heapDelta,
            r.status
        );
    }

    // ---- Helpers ----

    @FunctionalInterface
    private interface TokenGenerator {
        String generate(int index);
    }

    /**
     * @return total character count across all generated tokens
     */
    private static long writeSynonymFile(Path path, int targetTokens, TokenGenerator tokenGenerator) throws IOException {
        int lines = Math.max(1, targetTokens / 3);
        int tokenIndex = 0;
        long totalChars = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (int i = 0; i < lines; i++) {
                String t1 = tokenGenerator.generate(tokenIndex++);
                String t2 = tokenGenerator.generate(tokenIndex++);
                String t3 = tokenGenerator.generate(tokenIndex++);
                totalChars += t1.length() + t2.length() + t3.length();
                writer.write(t1);
                writer.write(", ");
                writer.write(t2);
                writer.write(", ");
                writer.write(t3);
                writer.newLine();
            }
        }
        return totalChars;
    }

    private static void suppressNoisyLoggers() {
        setLogLevel("org.elasticsearch.action.admin.indices.analyze.TransportReloadAnalyzersAction", Level.WARN);
        setLogLevel("org.elasticsearch.monitor.jvm.JvmGcMonitorService", Level.WARN);
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

    private static String rootCauseMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
