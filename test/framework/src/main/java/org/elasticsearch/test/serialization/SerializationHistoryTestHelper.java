/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.serialization;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Static-helper library for serialization golden-file (history) tests.
 *
 * <p>To use, implement {@link SerializationHistorySource} on your test class and call
 * {@link #assertHistoricalFormatsRoundTrip} from an explicit test method:
 * <pre>
 *   public void testHistoricalFormatsRoundTripToCurrentDefault() throws Exception {
 *       SerializationHistoryTestHelper.assertHistoricalFormatsRoundTrip(this, this::myRoundTrip);
 *   }
 * </pre>
 *
 * <h2>History file naming</h2>
 * <pre>
 *   {historyResourcePath()}/{major}_{typeName}_{transportVersionId}.json
 * </pre>
 */
public class SerializationHistoryTestHelper {

    private static final Logger LOGGER = LogManager.getLogger(SerializationHistoryTestHelper.class);
    private static final String TRANSPORT_VERSIONS_CSV = "/org/elasticsearch/TransportVersions.csv";

    private SerializationHistoryTestHelper() {}

    /**
     * Domain-specific round-trip function: parse historical JSON and re-serialize.
     */
    @FunctionalInterface
    public interface RoundTripper {
        String roundTrip(String typeName, String historicalJson) throws Exception;
    }

    /**
     * Verifies that for every history file on the classpath,
     * {@code roundTripper.roundTrip(typeName, historicalJson)} equals
     * {@code source.currentDefaults().get(typeName)}.
     *
     * <p>A mismatch means current code is not idempotent on old-format cluster state.
     */
    public static void assertHistoricalFormatsRoundTrip(SerializationHistorySource source, RoundTripper roundTripper)
        throws Exception {
        List<HistoryFile> historyFiles = discoverHistoryFiles(source);
        assertFalse(
            "No history files found under ["
                + source.historyResourcePath()
                + "] — check that test resources are on the classpath",
            historyFiles.isEmpty()
        );

        Map<String, String> defaults = source.currentDefaults();

        // Every type in currentDefaults() must have at least one history file, to ensure
        // BWC is tracked from the moment a type is introduced, not just after it first breaks.
        Set<String> coveredTypes = historyFiles.stream().map(HistoryFile::typeName).collect(Collectors.toSet());
        List<String> uncoveredTypes = defaults.keySet().stream().filter(t -> coveredTypes.contains(t) == false).sorted().toList();
        assertTrue(
            "The following types have no history files under ["
                + source.historyResourcePath()
                + "]. Add a history file via the generateSerializationHistory Gradle task: "
                + uncoveredTypes,
            uncoveredTypes.isEmpty()
        );

        for (HistoryFile hf : historyFiles) {
            String expected = defaults.get(hf.typeName());
            assertNotNull("No current default for type [" + hf.typeName() + "] — update currentDefaults()", expected);

            String actual = roundTripper.roundTrip(hf.typeName(), hf.json());

            assertEquals(
                "Historical format ["
                    + hf.fileName()
                    + "] (ES "
                    + hf.sinceMajor()
                    + ".x, transport version "
                    + hf.transportVersionId()
                    + ") does not round-trip to the current default for type ["
                    + hf.typeName()
                    + "].\n"
                    + "Current code serializes old-format cluster state differently from new data.\n"
                    + "In a rolling upgrade, a new node re-serializing a mapping read from an ES "
                    + hf.sinceMajor()
                    + ".x node will produce a different form, breaking DocumentMapper round-trip assertions.\n"
                    + "Fix the serialization to be idempotent, then add a history file for the new format\n"
                    + "if it will also need to be read by future versions.",
                expected,
                actual
            );
        }
    }

    /**
     * Reads {@code TransportVersions.csv} and returns a map from transport version ID to major ES
     * version (e.g. {@code 8841085 → 8} for {@code 8.19.13,8841085}).
     */
    public static Map<Integer, Integer> loadTransportVersionIdToMajor() throws IOException {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        try (
            InputStream is = SerializationHistoryTestHelper.class.getResourceAsStream(TRANSPORT_VERSIONS_CSV);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 2) continue;
                String releaseVersion = parts[0].trim();
                int transportVersionId = Integer.parseInt(parts[1].trim());
                int major = Integer.parseInt(releaseVersion.split("\\.")[0]);
                result.put(transportVersionId, major);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------

    private record HistoryFile(String fileName, int sinceMajor, String typeName, int transportVersionId, String json) {}

    /**
     * Probes for history files by cross-producting known type names (from
     * {@link SerializationHistorySource#currentDefaults()}) with transport version IDs from
     * {@code TransportVersions.csv}. Probing avoids classpath directory enumeration, which is
     * unreliable across JAR packaging formats.
     */
    private static List<HistoryFile> discoverHistoryFiles(SerializationHistorySource source) throws Exception {
        Map<Integer, Integer> versionIdToMajor = loadTransportVersionIdToMajor();
        Map<String, String> knownTypes = source.currentDefaults();
        List<HistoryFile> result = new ArrayList<>();
        String resourceBase = "/" + source.historyResourcePath() + "/";

        for (String typeName : knownTypes.keySet()) {
            for (Map.Entry<Integer, Integer> entry : versionIdToMajor.entrySet()) {
                int transportVersionId = entry.getKey();
                int major = entry.getValue();
                String candidate = major + "_" + typeName + "_" + transportVersionId + ".json";

                try (InputStream is = SerializationHistoryTestHelper.class.getResourceAsStream(resourceBase + candidate)) {
                    if (is == null) {
                        continue;
                    }
                    String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    result.add(new HistoryFile(candidate, major, typeName, transportVersionId, json));
                    LOGGER.info(
                        "Found history file [{}] for type [{}] (ES {}.x, transport version {})",
                        candidate,
                        typeName,
                        major,
                        transportVersionId
                    );
                }
            }
        }
        return result;
    }
}
