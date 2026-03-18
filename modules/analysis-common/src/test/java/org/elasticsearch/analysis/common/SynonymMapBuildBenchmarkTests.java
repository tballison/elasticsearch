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
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.synonyms.SynonymRule;
import org.elasticsearch.test.ESTestCase;

import java.io.StringReader;
import java.util.concurrent.TimeUnit;

/**
 * DO NOT MERGE THIS TEST INTO MAIN. This is for manual testing only.
 * <p>
 * Compares two approaches to building a {@link SynonymMap}:
 * <ol>
 *   <li><b>As-is</b>: {@link ESSolrSynonymParser} — text → HashMap → sort → FST</li>
 *   <li><b>Offline sort</b>: {@link SortedSynonymMapBuilder} — write pairs to disk,
 *       {@link org.apache.lucene.util.OfflineSorter} sort, stream into FST</li>
 * </ol>
 * Run with a constrained heap to find OOM thresholds, e.g.:
 * <pre>
 *   ./gradlew :modules:analysis-common:test \
 *     --tests "*.SynonymMapBuildBenchmarkTests" \
 *     -Dtests.jvm.argline="-Xmx512m"
 * </pre>
 */
public class SynonymMapBuildBenchmarkTests extends ESTestCase {

    private static final NoopCircuitBreaker NOOP_BREAKER = new NoopCircuitBreaker("noop");

    private static final int START_RULES = 200_000;
    private static final int STEP = 50_000;
    private static final int MAX_RULES = 10_000_000;

    public void testBuildOomThreshold() throws Exception {
        runBenchmark();
    }

    private void runBenchmark() throws Exception {
        StandardAnalyzer analyzer = new StandardAnalyzer();

        println("=== SynonymMap Build Benchmark (OOM threshold) ===");
        println("JVM max heap: " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        logHeader();

        int asIsLastGood = 0;
        int ourLastGood = 0;
        boolean asIsDead = false;
        boolean ourDead = false;

        for (int ruleCount = START_RULES; ruleCount <= MAX_RULES; ruleCount += STEP) {
            if (asIsDead && ourDead) break;

            SynonymRule[] rules = generateRules(ruleCount);

            // ── 1. As-is: ESSolrSynonymParser ────────────────────────────────
            String asIsStatus;
            if (asIsDead == false) {
                long start = System.nanoTime();
                try {
                    ESSolrSynonymParser parser = new ESSolrSynonymParser(true, true, false, analyzer, NOOP_BREAKER);
                    parser.parse(new StringReader(toText(rules)));
                    sink(parser.build());
                    asIsLastGood = ruleCount;
                    asIsStatus = ok(start);
                } catch (OutOfMemoryError e) {
                    asIsDead = true;
                    asIsStatus = "OOM";
                }
            } else {
                asIsStatus = "-";
            }

            // ── 2. Offline sort → streaming FSTCompiler ───────────────────────
            String ourStatus;
            if (ourDead == false) {
                long start = System.nanoTime();
                try {
                    try (SortedSynonymMapBuilder builder = new SortedSynonymMapBuilder()) {
                        builder.parse(rules, analyzer, true, false);
                        sink(builder.build());
                    }
                    ourLastGood = ruleCount;
                    ourStatus = ok(start);
                } catch (OutOfMemoryError e) {
                    ourDead = true;
                    ourStatus = "OOM";
                }
            } else {
                ourStatus = "-";
            }

            println(String.format(java.util.Locale.US, "%-8d | %-25s | %s", ruleCount, asIsStatus, ourStatus));
        }

        println("=== Results ===");
        println("As-is (ESSolrSynonymParser):  last OK at " + asIsLastGood);
        println("Offline sort → our builder:   last OK at " + ourLastGood);

        analyzer.close();
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
        println("Rules    | As-is (ESSolrSynonymParser) | Offline sort → our builder");
        println("---------|----------------------------|---------------------------");
    }
}
