/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.serialization;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Entry point for the {@code generateSerializationHistory} Gradle task.
 *
 * <p>The task forks a JVM with the module's {@code testRuntimeClasspath} and invokes this class.
 * It discovers all {@link SerializationHistorySource} implementations via {@link ServiceLoader},
 * calls {@link SerializationHistorySource#currentDefaults()} on each, and writes one JSON file
 * per type to a subdirectory of the output root mirroring {@link SerializationHistorySource#historyResourcePath()}.
 *
 * <h2>Output layout</h2>
 * <pre>
 *   &lt;outputRoot&gt;/
 *     org/elasticsearch/index/mapper/vectors/dense-vector-field-mapper-tests/
 *       int4_hnsw.json
 *       int4_flat.json
 *       ...
 * </pre>
 *
 * <p>The Gradle task then compares each file against the merge-base version of the corresponding
 * history resource file and writes new history files for any type whose output has changed.
 *
 * <h2>Invocation</h2>
 * <pre>
 *   java -cp &lt;testRuntimeClasspath&gt; \
 *        org.elasticsearch.test.serialization.SerializationHistoryLauncher \
 *        &lt;outputRoot&gt;
 * </pre>
 */
public class SerializationHistoryLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: SerializationHistoryLauncher <outputRoot>");
            System.exit(1);
        }
        Path outputRoot = Path.of(args[0]);
        Files.createDirectories(outputRoot);

        List<String> written = new ArrayList<>();
        for (SerializationHistorySource source : ServiceLoader.load(SerializationHistorySource.class)) {
            Path typeDir = outputRoot.resolve(source.historyResourcePath());
            Files.createDirectories(typeDir);
            for (Map.Entry<String, String> entry : source.currentDefaults().entrySet()) {
                Path out = typeDir.resolve(entry.getKey() + ".json");
                Files.writeString(out, entry.getValue());
                written.add(source.historyResourcePath() + "/" + entry.getKey() + ".json");
            }
        }

        if (written.isEmpty()) {
            System.err.println(
                "WARNING: No SerializationHistorySource implementations found on the classpath. "
                    + "Check META-INF/services registrations."
            );
            System.exit(1);
        }

        written.forEach(p -> System.out.println("Wrote: " + p));
    }
}
