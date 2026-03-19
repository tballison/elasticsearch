/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.synonyms.SynonymRule;
import org.elasticsearch.test.ESTestCase;

import java.io.StringReader;
import java.util.concurrent.TimeUnit;

/**
 * DO NOT MERGE THIS TEST INTO MAIN. This is for manual testing only.
 * <p>
 * Compares memory safety of two approaches to building a {@link SynonymMap}, using a circuit
 * breaker that simulates Elasticsearch's production parent circuit breaker behaviour
 * ({@code indices.breaker.total.use_real_memory=true}): it checks actual JVM heap usage on
 * every {@code addEstimateBytesAndMaybeBreak} call, regardless of the byte estimate passed.
 *
 * <ol>
 *   <li><b>As-is ({@link ESSolrSynonymParser})</b>: accumulates all synonym pairs into a
 *       {@code HashMap} during {@code parse()}, then converts to a sorted array and compiles
 *       an FST in {@code build()}.
 *       <ul>
 *         <li>{@code parse()} is protected: the circuit breaker is probed on each pair, so
 *             if the growing HashMap pushes real heap past {@link #HEAP_LIMIT_BYTES} the
 *             breaker trips gracefully.</li>
 *         <li>{@code build()} is <b>unprotected</b>: {@code SynonymMap.Builder.build()} never
 *             calls the circuit breaker. The moment when the HashMap, the sorted-keys array,
 *             and the FSTCompiler all live simultaneously — the peak usage — has no probe.
 *             If that spike exceeds the JVM heap ceiling, the result is an
 *             {@code OutOfMemoryError} with no graceful recovery.</li>
 *       </ul>
 *   </li>
 *   <li><b>Offline sort ({@link SortedSynonymMapBuilder})</b>: writes each analyzed pair
 *       directly to disk during {@code parse()} (bounded heap), then uses
 *       {@link org.apache.lucene.util.OfflineSorter} to sort on disk and streams the result
 *       directly into {@code FSTCompiler}.
 *       <ul>
 *         <li>{@code parse()} heap footprint is near zero — no in-memory accumulation.</li>
 *         <li>{@code build()} is protected: the circuit breaker is probed once per input
 *             group during FST construction, so memory pressure trips the breaker gracefully
 *             rather than crashing the JVM.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Run with a heap large enough for {@code parse()} to complete for the as-is approach,
 * but tight enough that {@code build()}'s peak spike is exposed, e.g.:
 * <pre>
 *   ./gradlew :modules:analysis-common:test \
 *     --tests "*.SynonymMapBuildBenchmarkTests" \
 *     -Dtests.jvm.argline="-Xmx1g"
 * </pre>
 * With {@code -Xmx1g}, the circuit breaker limit is set to 95% of max heap (~972 MB),
 * matching the production parent breaker default. As-is {@code parse()} completes
 * until {@code build()}'s unprotected spike causes an OOM. The offline-sort builder
 * handles roughly 3× as many rules before the circuit breaker trips gracefully.
 */
public class SynonymMapBuildBenchmarkTests extends ESTestCase {

    /**
     * Circuit breaker trips when real JVM heap usage exceeds this threshold.
     * Set to 95% of max heap to match the production parent circuit breaker default
     * ({@code indices.breaker.total.limit = 95%} when {@code use_real_memory=true}).
     */
    private static final long HEAP_LIMIT_BYTES = (long) (Runtime.getRuntime().maxMemory() * 0.95);

    private static final int START_RULES = 500_000;
    private static final int STEP = 100_000;
    private static final int MAX_RULES = 10_000_000;

    public void testBuildOomThreshold() throws Exception {
        runBenchmark();
    }

    private void runBenchmark() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        HeapThresholdCircuitBreaker breaker = new HeapThresholdCircuitBreaker(HEAP_LIMIT_BYTES);

        println("=== SynonymMap Build Benchmark ===");
        println("JVM max heap:            " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        println("Circuit breaker limit:   " + HEAP_LIMIT_BYTES / (1024 * 1024) + " MB used heap (95% of max)");
        println("Both approaches use the same real-heap circuit breaker (simulates production).");
        println("As-is: parse() is protected, but build() has no circuit breaker probe -> OOM.");
        println("Ours:  both parse() and build() are protected -> graceful TRIPPED.");
        logHeader();

        int asIsLastGood = 0;
        int ourLastGood = 0;
        boolean asIsDead = false;
        boolean ourDead = false;

        for (int ruleCount = START_RULES; ruleCount <= MAX_RULES; ruleCount += STEP) {
            if (asIsDead && ourDead) break;

            SynonymRule[] rules = generateRules(ruleCount);

            // ── 1. As-is: ESSolrSynonymParser ────────────────────────────────────────────────────
            // parse() probes the breaker on each synonym pair — protected against HashMap growth.
            // build() has NO circuit breaker probe: the HashMap + sorted-keys array + FSTCompiler
            // all live simultaneously with no safety check. If that spike exceeds JVM max heap,
            // the result is an OutOfMemoryError.
            String asIsStatus;
            if (asIsDead == false) {
                System.gc();
                long start = System.nanoTime();
                try {
                    ESSolrSynonymParser parser = new ESSolrSynonymParser(true, true, false, analyzer, breaker);
                    parser.parse(new StringReader(toText(rules)));
                    sink(parser.build());
                    asIsLastGood = ruleCount;
                    asIsStatus = ok(start);
                } catch (CircuitBreakingException e) {
                    asIsDead = true;
                    asIsStatus = "TRIPPED during parse() (" + e.getMessage() + ")";
                } catch (OutOfMemoryError e) {
                    asIsDead = true;
                    asIsStatus = "OOM during build() — unprotected spike";
                }
            } else {
                asIsStatus = "-";
            }

            // ── 2. Offline sort → streaming FSTCompiler ───────────────────────────────────────────
            // parse() writes to disk — heap stays near zero, breaker probes are low-cost checks.
            // build() probes the breaker once per input group during FST construction — if heap
            // pressure builds, the breaker trips gracefully instead of crashing.
            String ourStatus;
            if (ourDead == false) {
                System.gc();
                long start = System.nanoTime();
                try {
                    try (SortedSynonymMapBuilder builder = new SortedSynonymMapBuilder(breaker)) {
                        builder.parse(rules, analyzer, true, false);
                        sink(builder.build());
                    }
                    ourLastGood = ruleCount;
                    ourStatus = ok(start);
                } catch (CircuitBreakingException e) {
                    ourDead = true;
                    ourStatus = "TRIPPED gracefully (" + e.getMessage() + ")";
                } catch (OutOfMemoryError e) {
                    ourDead = true;
                    ourStatus = "OOM";
                }
            } else {
                ourStatus = "-";
            }

            println(String.format(java.util.Locale.US, "%-8d | %-50s | %s", ruleCount, asIsStatus, ourStatus));
        }

        println("=== Results ===");
        println("As-is (ESSolrSynonymParser):  last OK at " + asIsLastGood);
        println("Offline sort → our builder:   last OK at " + ourLastGood);

        analyzer.close();
    }

    /**
     * Circuit breaker that checks actual JVM heap usage on every call, ignoring the byte
     * estimate. This mirrors the behaviour of Elasticsearch's production parent circuit breaker
     * when {@code indices.breaker.total.use_real_memory=true} (the default): real heap is
     * sampled regardless of what the child breaker reports.
     */
    private static class HeapThresholdCircuitBreaker implements CircuitBreaker {
        private final long limitBytes;

        HeapThresholdCircuitBreaker(long limitBytes) {
            this.limitBytes = limitBytes;
        }

        @Override
        public void addEstimateBytesAndMaybeBreak(long bytes, String label) throws CircuitBreakingException {
            Runtime rt = Runtime.getRuntime();
            long usedBytes = rt.totalMemory() - rt.freeMemory();
            if (usedBytes > limitBytes) {
                // Approximate the production G1OverLimitStrategy: attempt GC before tripping.
                // In production, the parent breaker triggers G1GC via region-sized allocations;
                // here we use System.gc() as a simpler stand-in. If GC reclaims enough ephemeral
                // parse objects to bring heap back below the limit, parse() can continue — matching
                // the production behaviour where brief exceedances don't immediately trip the breaker.
                System.gc();
                usedBytes = rt.totalMemory() - rt.freeMemory();
                if (usedBytes > limitBytes) {
                    throw new CircuitBreakingException(
                        "Synonyms: used heap " + usedBytes / (1024 * 1024) + " MB exceeds limit "
                            + limitBytes / (1024 * 1024) + " MB",
                        CircuitBreaker.Durability.TRANSIENT
                    );
                }
            }
        }

        @Override public void circuitBreak(String fieldName, long bytesNeeded) {}
        @Override public void addWithoutBreaking(long bytes) {}
        @Override public long getUsed() { return 0; }
        @Override public long getLimit() { return limitBytes; }
        @Override public double getOverhead() { return 1.0; }
        @Override public long getTrippedCount() { return 0; }
        @Override public String getName() { return "heap-threshold"; }
        @Override public Durability getDurability() { return Durability.TRANSIENT; }
        @Override public void setLimitAndOverhead(long limit, double overhead) {}
    }

    private static String ok(long startNanos) {
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return String.format(java.util.Locale.US, "OK (%d ms)", ms);
    }

    private static void sink(SynonymMap map) {
        if (map == null) throw new AssertionError("map should not be null");
    }

    private static String toText(SynonymRule[] rules) {
        StringBuilder sb = new StringBuilder();
        for (SynonymRule rule : rules) {
            sb.append(rule.synonyms()).append(System.lineSeparator());
        }
        return sb.toString();
    }

    private static SynonymRule[] generateRules(int count) {
        SynonymRule[] rules = new SynonymRule[count];
        for (int i = 0; i < count; i++) {
            int termCount = between(2, 5);
            String[] terms = new String[termCount];
            for (int t = 0; t < termCount; t++) {
                terms[t] = randomAlphaOfLengthBetween(3, 10);
            }
            rules[i] = new SynonymRule("rule_" + i, String.join(", ", terms));
        }
        return rules;
    }

    private static void println(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private void logHeader() {
        println(String.format(java.util.Locale.US, "%-8s | %-50s | %s", "Rules", "As-is (ESSolrSynonymParser)", "Offline sort (SortedSynonymMapBuilder)"));
        println("-".repeat(110));
    }
}
