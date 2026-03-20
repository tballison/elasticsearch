/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.test.serialization;

import java.util.Locale;
import java.util.Map;

/**
 * Implemented by classes that own serializable formats whose history should be tracked for BWC.
 *
 * <p>The Gradle {@code generateSerializationHistory} task discovers all implementations on the
 * test classpath via {@link java.util.ServiceLoader}, calls {@link #currentDefaults()}, and
 * compares the output against the merge-base version of each type's history file. When the output
 * has changed, a new history file is written and {@code git add}ed automatically.
 *
 * <h2>File naming and location</h2>
 *
 * History files live at:
 * <pre>
 *   src/test/resources/{@link #historyResourcePath()}/{major}_{typeName}_{transportVersionId}.json
 * </pre>
 *
 * {@link #historyResourcePath()} defaults to {@code {package-as-path}/{class-name-in-kebab-case}},
 * which is correct for most test classes. Override it when the implementing class is an inner class
 * or helper registered via {@link java.util.ServiceLoader} whose name does not reflect the domain.
 */
public interface SerializationHistorySource {

    /**
     * Classpath-rooted path to the directory containing this source's history files.
     *
     * <p>Default: the implementing class's package as a path, followed by the simple class name
     * in kebab-case. For example, {@code DenseVectorFieldMapperTests} in package
     * {@code org.elasticsearch.index.mapper.vectors} →
     * {@code org/elasticsearch/index/mapper/vectors/dense-vector-field-mapper-tests}.
     *
     * <p>Override when the class name does not reflect the serialization domain (e.g. inner
     * classes registered via {@link java.util.ServiceLoader}).
     */
    default String historyResourcePath() {
        String name = getClass().getSimpleName();
        // CamelCase → kebab-case
        name = name.replaceAll("([a-z\\d])([A-Z])", "$1-$2");
        name = name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2");
        name = name.toLowerCase(Locale.ROOT);
        return getClass().getPackageName().replace('.', '/') + "/" + name;
    }

    /**
     * Returns the current default serialization for every format this source owns.
     *
     * @return map from stable type name (filename stem) to pretty-printed JSON ending with {@code \n}
     */
    Map<String, String> currentDefaults() throws Exception;
}
