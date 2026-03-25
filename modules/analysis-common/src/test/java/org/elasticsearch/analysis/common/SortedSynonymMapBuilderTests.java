/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.synonyms.SynonymRule;
import org.elasticsearch.test.ESTokenStreamTestCase;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.apache.lucene.tests.analysis.BaseTokenStreamTestCase.assertTokenStreamContents;

/**
 * Verifies that {@link SortedSynonymMapBuilder} produces a {@link SynonymMap} equivalent
 * to the standard {@link ESSolrSynonymParser}.
 */
public class SortedSynonymMapBuilderTests extends ESTokenStreamTestCase {

    private static final NoopCircuitBreaker NOOP_BREAKER = new NoopCircuitBreaker("noop");

    private static SynonymMap buildWithParser(String rules, Analyzer analyzer, boolean expand) throws Exception {
        ESSolrSynonymParser parser = new ESSolrSynonymParser(true, expand, false, analyzer, NOOP_BREAKER);
        parser.parse(new StringReader(rules));
        return parser.build();
    }

    private static SynonymMap buildWithSortedBuilder(SynonymRule[] rules, Analyzer analyzer, boolean expand)
        throws Exception {
        try (SortedSynonymMapBuilder builder = new SortedSynonymMapBuilder(NOOP_BREAKER, createTempDir())) {
            ESSolrSynonymParser parser = new ESSolrSynonymParser(true, expand, false, analyzer, builder);
            parser.parse(rulesToReader(rules));
            return parser.build();
        }
    }

    private static StringReader rulesToReader(SynonymRule[] rules) {
        return new StringReader(rulesToString(rules));
    }

    private static SynonymRule[] rules(String... synonymStrings) {
        SynonymRule[] rules = new SynonymRule[synonymStrings.length];
        for (int i = 0; i < synonymStrings.length; i++) {
            rules[i] = new SynonymRule("rule_" + i, synonymStrings[i]);
        }
        return rules;
    }

    // ── explicit mappings ──────────────────────────────────────────────────────

    public void testExplicitMapping() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymMap map = buildWithSortedBuilder(rules("fruit => apple, orange, banana"), analyzer, false);

        var tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader("fruit"));
        TokenStream ts = new SynonymFilter(tokenizer, map, false);
        assertTokenStreamContents(ts, new String[] { "apple", "orange", "banana" });
    }

    public void testExplicitMappingMultipleInputs() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymMap map = buildWithSortedBuilder(rules("usa, us => united states"), analyzer, false);

        for (String input : new String[] { "usa", "us" }) {
            var tokenizer = new StandardTokenizer();
            tokenizer.setReader(new StringReader(input));
            TokenStream ts = new SynonymFilter(tokenizer, map, false);
            assertTokenStreamContents(ts, new String[] { "united", "states" });
        }
    }

    // ── equivalent mappings ───────────────────────────────────────────────────

    public void testEquivalentMappingNoExpand() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymMap map = buildWithSortedBuilder(rules("apple, orange, banana"), analyzer, false);

        // With expand=false, non-first terms map to the first
        var tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader("orange"));
        TokenStream ts = new SynonymFilter(tokenizer, map, false);
        assertTokenStreamContents(ts, new String[] { "apple" });

        // First term passes through unchanged
        tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader("apple"));
        ts = new SynonymFilter(tokenizer, map, false);
        assertTokenStreamContents(ts, new String[] { "apple" });
    }

    public void testEquivalentMappingExpand() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymMap parserMap = buildWithParser("apple, orange, banana\n", analyzer, true);
        SynonymMap sortedMap = buildWithSortedBuilder(rules("apple, orange, banana"), analyzer, true);

        // All three terms should be present in the output for any input term
        assertSameTokens(parserMap, sortedMap, "orange");
        assertSameTokens(parserMap, sortedMap, "apple");
        assertSameTokens(parserMap, sortedMap, "banana");
    }

    // ── multi-word synonyms ───────────────────────────────────────────────────

    public void testMultiWordOutput() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymMap map = buildWithSortedBuilder(rules("us => united states"), analyzer, false);

        var tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader("us"));
        TokenStream ts = new SynonymFilter(tokenizer, map, false);
        assertTokenStreamContents(ts, new String[] { "united", "states" });
    }

    // ── empty and lenient ─────────────────────────────────────────────────────

    public void testEmptyRules() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymMap map = buildWithSortedBuilder(new SynonymRule[0], analyzer, true);
        // empty map: fst is null, so tokens pass through unchanged
        assertNull(map.fst);
    }

    public void testBlankAndNullSynonymsSkipped() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymRule[] rules = new SynonymRule[] {
            new SynonymRule("r1", "  "),
            new SynonymRule("r2", "apple => fruit")
        };
        try (SortedSynonymMapBuilder builder = new SortedSynonymMapBuilder(NOOP_BREAKER, createTempDir())) {
            ESSolrSynonymParser parser = new ESSolrSynonymParser(true, false, true, analyzer, builder);
            parser.parse(rulesToReader(rules));
            SynonymMap map = parser.build();

            var tokenizer = new StandardTokenizer();
            tokenizer.setReader(new StringReader("apple"));
            TokenStream ts = new SynonymFilter(tokenizer, map, false);
            assertTokenStreamContents(ts, new String[] { "fruit" });
        }
    }

    // ── parity with ESSolrSynonymParser ──────────────────────────────────────

    public void testParityWithParserExplicit() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymMap parserMap = buildWithParser("fruit => apple\n", analyzer, false);
        SynonymMap sortedMap = buildWithSortedBuilder(rules("fruit => apple"), analyzer, false);

        assertSameTokens(parserMap, sortedMap, "fruit");
        assertSameTokens(parserMap, sortedMap, "apple");
    }

    public void testParityWithParserNoExpand() throws Exception {
        Analyzer analyzer = new StandardAnalyzer();
        SynonymMap parserMap = buildWithParser("come, advance, approach\n", analyzer, false);
        SynonymMap sortedMap = buildWithSortedBuilder(rules("come, advance, approach"), analyzer, false);

        assertSameTokens(parserMap, sortedMap, "come");
        assertSameTokens(parserMap, sortedMap, "advance");
        assertSameTokens(parserMap, sortedMap, "approach");
    }

    // ── randomized parity ─────────────────────────────────────────────────────

    public void testRandomizedParity() throws Exception {
        Random rng = random();
        Analyzer analyzer = new StandardAnalyzer();

        int numRules = 10 + rng.nextInt(40);
        boolean expand = rng.nextBoolean();
        String[] termPool = randomTermPool(rng, 20);
        SynonymRule[] rules = randomRules(rng, termPool, numRules);

        SynonymMap inMemoryMap = buildWithParser(rulesToString(rules), analyzer, expand);
        SynonymMap diskMap = buildWithSortedBuilder(rules, analyzer, expand);

        // probe every term in the pool against both maps
        for (String term : termPool) {
            assertSameTokens(inMemoryMap, diskMap, term);
        }
    }

    private static String[] randomTermPool(Random rng, int size) {
        String[] pool = new String[size];
        for (int i = 0; i < size; i++) {
            int len = 3 + rng.nextInt(6);
            char[] chars = new char[len];
            for (int j = 0; j < len; j++) {
                chars[j] = (char) ('a' + rng.nextInt(26));
            }
            pool[i] = new String(chars);
        }
        return pool;
    }

    private static SynonymRule[] randomRules(Random rng, String[] termPool, int count) {
        SynonymRule[] rules = new SynonymRule[count];
        for (int i = 0; i < count; i++) {
            boolean explicit = rng.nextBoolean();
            if (explicit) {
                // "t1, t2 => t3, t4"
                int numInputs = 1 + rng.nextInt(2);
                int numOutputs = 1 + rng.nextInt(2);
                String inputs = randomTerms(rng, termPool, numInputs);
                String outputs = randomTerms(rng, termPool, numOutputs);
                rules[i] = new SynonymRule("rule_" + i, inputs + " => " + outputs);
            } else {
                // "t1, t2, t3"
                int numTerms = 2 + rng.nextInt(3);
                rules[i] = new SynonymRule("rule_" + i, randomTerms(rng, termPool, numTerms));
            }
        }
        return rules;
    }

    private static String randomTerms(Random rng, String[] pool, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pool[rng.nextInt(pool.length)]);
        }
        return sb.toString();
    }

    private static String rulesToString(SynonymRule[] rules) {
        StringBuilder sb = new StringBuilder();
        for (SynonymRule rule : rules) {
            sb.append(rule.synonyms()).append(System.lineSeparator());
        }
        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String[] getTokens(SynonymMap map, String input) throws IOException {
        if (map.fst == null) {
            return new String[] { input };
        }
        var tokenizer = new StandardTokenizer();
        tokenizer.setReader(new StringReader(input));
        TokenStream ts = new SynonymFilter(tokenizer, map, false);
        CharTermAttribute termAtt = ts.addAttribute(CharTermAttribute.class);
        ts.reset();
        List<String> tokens = new ArrayList<>();
        while (ts.incrementToken()) {
            tokens.add(termAtt.toString());
        }
        ts.end();
        ts.close();
        return tokens.toArray(new String[0]);
    }

    private static String[] getSortedTokens(SynonymMap map, String input) throws IOException {
        String[] tokens = getTokens(map, input);
        Arrays.sort(tokens);
        return tokens;
    }

    private void assertSameTokens(SynonymMap expected, SynonymMap actual, String input) throws IOException {
        String[] exp = getTokens(expected, input);
        String[] act = getTokens(actual, input);
        Arrays.sort(exp);
        Arrays.sort(act);
        assertArrayEquals(
            "Mismatch for '" + input + "': expected " + Arrays.toString(exp) + " but got " + Arrays.toString(act),
            exp,
            act
        );
    }
}
