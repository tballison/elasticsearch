/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.analysis.common;

import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.util.CharsRef;

import java.io.IOException;

/**
 * Common storage backend for synonym pair accumulation. Implementations may
 * store pairs in memory ({@link ESSynonymMapBuilder}) or spill to disk
 * ({@link SortedSynonymMapBuilder}) before building the final {@link SynonymMap}.
 */
interface SynonymPairAcceptor {
    void add(CharsRef input, CharsRef output, boolean includeOrig);

    SynonymMap build() throws IOException;
}
