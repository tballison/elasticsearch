/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.vectors;

import org.elasticsearch.index.query.QueryBuilder;

import java.util.List;

/**
 * A {@link QueryBuilder} that exposes the parameters of a kNN vector search. Implemented by query builders that represent or wrap a
 * top-level kNN vector search (e.g. {@link KnnVectorQueryBuilder} and its intercepted forms), allowing callers to consume kNN search
 * parameters polymorphically without depending on a specific implementation.
 */
public interface KnnSearchQuery extends QueryBuilder {

    String getFieldName();

    VectorData queryVector();

    QueryVectorBuilder queryVectorBuilder();

    Integer k();

    Integer numCands();

    Float visitPercentage();

    Float getVectorSimilarity();

    RescoreVectorBuilder rescoreVectorBuilder();

    List<QueryBuilder> filterQueries();
}