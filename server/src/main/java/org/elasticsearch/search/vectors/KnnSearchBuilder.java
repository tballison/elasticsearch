/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.vectors;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.InnerHitBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.Rewriteable;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.common.Strings.format;
import static org.elasticsearch.index.query.AbstractQueryBuilder.DEFAULT_BOOST;
import static org.elasticsearch.search.SearchService.DEFAULT_SIZE;
import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.xcontent.ConstructingObjectParser.optionalConstructorArg;

/**
 * Defines a kNN search to run in the top-level {@code knn} section of a search request.
 *
 * <p>This class is a thin envelope around an inner {@link QueryBuilder} that holds all of the kNN search state
 * (field, query vector, k, num_candidates, similarity, filters, boost, queryName, rescore vector). The inner is
 * constructed as a {@link KnnVectorQueryBuilder} in every public entry point. Delegating the rewrite to the inner
 * routes through {@link AbstractQueryBuilder#rewrite}, which is the entry point of the
 * {@code QueryRewriteInterceptor} framework — so the knn section participates in interception (e.g. for
 * {@code semantic_text} fields) the same way the {@code knn} query does. The envelope keeps only what the inner
 * does not model: {@link #innerHitBuilder}.
 *
 * <p>Read accessors and setters on the envelope delegate to the inner via {@link KnnSearchQuery}. They throw
 * {@link IllegalStateException} if the inner has been rewritten to a form that is not a {@code KnnSearchQuery}
 * (for example, an intercepted wrapper, a nested wrapper around an intercepted kNN, or {@code MatchNoneQueryBuilder}).
 * Such a state is only reachable on the coordinator after rewrite has begun; the search source machinery is
 * expected to extract intercepted kNN section entries to {@code subSearchSourceBuilders} before the rewritten
 * envelope reaches an accessor or the wire.
 */
public class KnnSearchBuilder implements Writeable, ToXContentFragment, Rewriteable<KnnSearchBuilder> {
    public static final int NUM_CANDS_LIMIT = 10_000;
    public static final float NUM_CANDS_MULTIPLICATIVE_FACTOR = 1.5f;

    public static final ParseField FIELD_FIELD = new ParseField("field");
    public static final ParseField K_FIELD = new ParseField("k");
    public static final ParseField NUM_CANDS_FIELD = new ParseField("num_candidates");
    public static final ParseField VISIT_PERCENTAGE_FIELD = new ParseField("visit_percentage");
    public static final ParseField QUERY_VECTOR_FIELD = new ParseField("query_vector");
    public static final ParseField QUERY_VECTOR_BUILDER_FIELD = new ParseField("query_vector_builder");
    public static final ParseField VECTOR_SIMILARITY = new ParseField("similarity");
    public static final ParseField FILTER_FIELD = new ParseField("filter");
    public static final ParseField NAME_FIELD = AbstractQueryBuilder.NAME_FIELD;
    public static final ParseField BOOST_FIELD = AbstractQueryBuilder.BOOST_FIELD;
    public static final ParseField INNER_HITS_FIELD = new ParseField("inner_hits");
    public static final ParseField RESCORE_VECTOR_FIELD = new ParseField("rescore_vector");

    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<KnnSearchBuilder.Builder, Void> PARSER = new ConstructingObjectParser<>("knn", args -> {
        // TODO optimize parsing for when BYTE values are provided
        return new Builder().field((String) args[0])
            .queryVector((VectorData) args[1])
            .queryVectorBuilder((QueryVectorBuilder) args[5])
            .k((Integer) args[2])
            .numCandidates((Integer) args[3])
            .visitPercentage((Float) args[4])
            .similarity((Float) args[6])
            .rescoreVectorBuilder((RescoreVectorBuilder) args[7]);
    });

    static {
        PARSER.declareString(constructorArg(), FIELD_FIELD);
        PARSER.declareField(
            optionalConstructorArg(),
            (p, c) -> VectorData.parseXContent(p),
            QUERY_VECTOR_FIELD,
            ObjectParser.ValueType.OBJECT_ARRAY_STRING_OR_NUMBER
        );
        PARSER.declareInt(optionalConstructorArg(), K_FIELD);
        PARSER.declareInt(optionalConstructorArg(), NUM_CANDS_FIELD);
        PARSER.declareFloat(optionalConstructorArg(), VISIT_PERCENTAGE_FIELD);
        PARSER.declareNamedObject(
            optionalConstructorArg(),
            (p, c, n) -> p.namedObject(QueryVectorBuilder.class, n, c),
            QUERY_VECTOR_BUILDER_FIELD
        );
        PARSER.declareFloat(optionalConstructorArg(), VECTOR_SIMILARITY);
        PARSER.declareField(
            optionalConstructorArg(),
            (p, c) -> RescoreVectorBuilder.fromXContent(p),
            RESCORE_VECTOR_FIELD,
            ObjectParser.ValueType.OBJECT
        );
        PARSER.declareFieldArray(
            KnnSearchBuilder.Builder::addFilterQueries,
            (p, c) -> AbstractQueryBuilder.parseTopLevelQuery(p),
            FILTER_FIELD,
            ObjectParser.ValueType.OBJECT_ARRAY
        );
        PARSER.declareString(KnnSearchBuilder.Builder::queryName, NAME_FIELD);
        PARSER.declareFloat(KnnSearchBuilder.Builder::boost, BOOST_FIELD);
        PARSER.declareField(
            KnnSearchBuilder.Builder::innerHit,
            (p, c) -> InnerHitBuilder.fromXContent(p),
            INNER_HITS_FIELD,
            ObjectParser.ValueType.OBJECT
        );
    }

    public static KnnSearchBuilder.Builder fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }

    private static final TransportVersion VISIT_PERCENTAGE = TransportVersion.fromName("visit_percentage");

    private final QueryBuilder innerQuery;
    InnerHitBuilder innerHitBuilder;

    /**
     * Defines a kNN search.
     *
     * @param field       the name of the vector field to search against
     * @param queryVector the query vector
     * @param k           the final number of nearest neighbors to return as top hits
     * @param numCands    the number of nearest neighbor candidates to consider per shard
     * @param visitPercentage percentage of the total number of vectors to visit per shard
     * @param rescoreVectorBuilder rescore vector information
     */
    public KnnSearchBuilder(
        String field,
        float[] queryVector,
        int k,
        int numCands,
        Float visitPercentage,
        RescoreVectorBuilder rescoreVectorBuilder,
        Float similarity
    ) {
        this(new KnnVectorQueryBuilder(field, queryVector, k, numCands, visitPercentage, rescoreVectorBuilder, similarity), null);
    }

    /**
     * Defines a kNN search.
     *
     * @param field       the name of the vector field to search against
     * @param queryVector the query vector
     * @param k           the final number of nearest neighbors to return as top hits
     * @param numCands    the number of nearest neighbor candidates to consider per shard
     * @param visitPercentage percentage of the total number of vectors to visit per shard
     */
    public KnnSearchBuilder(
        String field,
        VectorData queryVector,
        int k,
        int numCands,
        Float visitPercentage,
        RescoreVectorBuilder rescoreVectorBuilder,
        Float similarity
    ) {
        this(new KnnVectorQueryBuilder(field, queryVector, k, numCands, visitPercentage, rescoreVectorBuilder, similarity), null);
    }

    /**
     * Defines a kNN search where the query vector will be provided by the queryVectorBuilder
     *
     * @param field              the name of the vector field to search against
     * @param queryVectorBuilder the query vector builder
     * @param k                  the final number of nearest neighbors to return as top hits
     * @param numCands           the number of nearest neighbor candidates to consider per shard
     * @param visitPercentage    percentage of the total number of vectors to visit per shard
     */
    public KnnSearchBuilder(
        String field,
        QueryVectorBuilder queryVectorBuilder,
        int k,
        int numCands,
        Float visitPercentage,
        RescoreVectorBuilder rescoreVectorBuilder,
        Float similarity
    ) {
        this(
            new KnnVectorQueryBuilder(
                field,
                Objects.requireNonNull(queryVectorBuilder, format("[%s] cannot be null", QUERY_VECTOR_BUILDER_FIELD.getPreferredName())),
                k,
                numCands,
                visitPercentage,
                rescoreVectorBuilder,
                similarity
            ),
            null
        );
    }

    public KnnSearchBuilder(
        String field,
        VectorData queryVector,
        QueryVectorBuilder queryVectorBuilder,
        int k,
        int numCands,
        Float visitPercentage,
        RescoreVectorBuilder rescoreVectorBuilder,
        Float similarity
    ) {
        this(buildInner(field, queryVector, queryVectorBuilder, k, numCands, visitPercentage, rescoreVectorBuilder, similarity), null);
    }

    private static KnnVectorQueryBuilder buildInner(
        String field,
        VectorData queryVector,
        QueryVectorBuilder queryVectorBuilder,
        int k,
        int numCands,
        Float visitPercentage,
        RescoreVectorBuilder rescoreVectorBuilder,
        Float similarity
    ) {
        if (queryVector != null && queryVectorBuilder != null) {
            throw new IllegalArgumentException(
                format(
                    "cannot provide both [%s] and [%s]",
                    QUERY_VECTOR_BUILDER_FIELD.getPreferredName(),
                    QUERY_VECTOR_FIELD.getPreferredName()
                )
            );
        }
        if (queryVectorBuilder != null) {
            return new KnnVectorQueryBuilder(field, queryVectorBuilder, k, numCands, visitPercentage, rescoreVectorBuilder, similarity);
        }
        return new KnnVectorQueryBuilder(field, queryVector, k, numCands, visitPercentage, rescoreVectorBuilder, similarity);
    }

    private KnnSearchBuilder(QueryBuilder innerQuery, InnerHitBuilder innerHitBuilder) {
        this.innerQuery = Objects.requireNonNull(innerQuery);
        this.innerHitBuilder = innerHitBuilder;
    }

    public KnnSearchBuilder(StreamInput in) throws IOException {
        String field = in.readString();
        int k = in.readVInt();
        int numCands = in.readVInt();
        Float visitPercentage = in.getTransportVersion().supports(VISIT_PERCENTAGE) ? in.readOptionalFloat() : null;
        VectorData queryVector = in.readOptionalWriteable(VectorData::new);
        List<QueryBuilder> filterQueries = in.readNamedWriteableCollectionAsList(QueryBuilder.class);
        float boost = in.readFloat();
        String queryName = in.readOptionalString();
        QueryVectorBuilder queryVectorBuilder = in.readOptionalNamedWriteable(QueryVectorBuilder.class);
        Float similarity = in.readOptionalFloat();
        this.innerHitBuilder = in.readOptionalWriteable(InnerHitBuilder::new);
        RescoreVectorBuilder rescoreVectorBuilder = in.readOptional(RescoreVectorBuilder::new);

        KnnVectorQueryBuilder inner = queryVectorBuilder != null
            ? new KnnVectorQueryBuilder(field, queryVectorBuilder, k, numCands, visitPercentage, rescoreVectorBuilder, similarity)
            : new KnnVectorQueryBuilder(field, queryVector, k, numCands, visitPercentage, rescoreVectorBuilder, similarity);
        inner.boost(boost).queryName(queryName).addFilterQueries(filterQueries);
        this.innerQuery = inner;
    }

    /**
     * Returns the inner query that holds the kNN search state. After {@link #rewrite}, this can be any
     * {@link QueryBuilder} (for example, the intercepted form when the field is a {@code semantic_text}). Pre-rewrite
     * and on the only-non-semantic path it is always a {@link KnnVectorQueryBuilder}.
     */
    public QueryBuilder toQueryBuilder() {
        return innerQuery;
    }

    /**
     * Cast the inner to {@link KnnSearchQuery} for accessor delegation. Pre-rewrite (and post-rewrite for
     * non-intercepted kNN sections) the inner is always a {@link KnnVectorQueryBuilder} and therefore a
     * {@code KnnSearchQuery}.
     */
    private KnnSearchQuery asKnnSearchQuery() {
        if (innerQuery instanceof KnnSearchQuery knnSearchQuery) {
            return knnSearchQuery;
        }
        throw new IllegalStateException(
            "kNN section accessor invoked on rewritten inner of type [" + innerQuery.getClass().getSimpleName() + "]"
        );
    }

    private KnnVectorQueryBuilder asKnnVectorQueryBuilder() {
        if (innerQuery instanceof KnnVectorQueryBuilder knnVectorQueryBuilder) {
            return knnVectorQueryBuilder;
        }
        throw new IllegalStateException(
            "kNN section mutator invoked on rewritten inner of type [" + innerQuery.getClass().getSimpleName() + "]"
        );
    }

    public int k() {
        return asKnnSearchQuery().k();
    }

    public int getNumCands() {
        return asKnnSearchQuery().numCands();
    }

    public Float getVisitPercentage() {
        return asKnnSearchQuery().visitPercentage();
    }

    public RescoreVectorBuilder getRescoreVectorBuilder() {
        return asKnnSearchQuery().rescoreVectorBuilder();
    }

    public QueryVectorBuilder getQueryVectorBuilder() {
        return asKnnSearchQuery().queryVectorBuilder();
    }

    // for testing only
    public VectorData getQueryVector() {
        return asKnnSearchQuery().queryVector();
    }

    public String getField() {
        return asKnnSearchQuery().getFieldName();
    }

    public List<QueryBuilder> getFilterQueries() {
        return asKnnSearchQuery().filterQueries();
    }

    public Float getSimilarity() {
        return asKnnSearchQuery().getVectorSimilarity();
    }

    public KnnSearchBuilder addFilterQuery(QueryBuilder filterQuery) {
        Objects.requireNonNull(filterQuery);
        asKnnVectorQueryBuilder().addFilterQuery(filterQuery);
        return this;
    }

    public KnnSearchBuilder addFilterQueries(List<QueryBuilder> filterQueries) {
        Objects.requireNonNull(filterQueries);
        asKnnVectorQueryBuilder().addFilterQueries(filterQueries);
        return this;
    }

    /**
     * Sets a query name for the kNN search query.
     */
    public KnnSearchBuilder queryName(String queryName) {
        innerQuery.queryName(queryName);
        return this;
    }

    public String queryName() {
        return innerQuery.queryName();
    }

    /**
     * Set a boost to apply to the kNN search scores.
     */
    public KnnSearchBuilder boost(float boost) {
        innerQuery.boost(boost);
        return this;
    }

    public float boost() {
        return innerQuery.boost();
    }

    public KnnSearchBuilder innerHit(InnerHitBuilder innerHitBuilder) {
        this.innerHitBuilder = innerHitBuilder;
        return this;
    }

    public InnerHitBuilder innerHit() {
        return innerHitBuilder;
    }

    @Override
    public KnnSearchBuilder rewrite(QueryRewriteContext ctx) throws IOException {
        QueryBuilder rewrittenInner = innerQuery.rewrite(ctx);
        if (rewrittenInner == innerQuery) {
            return this;
        }
        return new KnnSearchBuilder(rewrittenInner, innerHitBuilder);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KnnSearchBuilder that = (KnnSearchBuilder) o;
        return Objects.equals(innerQuery, that.innerQuery) && Objects.equals(innerHitBuilder, that.innerHitBuilder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(innerQuery, innerHitBuilder);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        KnnSearchQuery inner = asKnnSearchQuery();
        builder.field(FIELD_FIELD.getPreferredName(), inner.getFieldName());
        builder.field(K_FIELD.getPreferredName(), inner.k());
        builder.field(NUM_CANDS_FIELD.getPreferredName(), inner.numCands());

        if (inner.visitPercentage() != null) {
            builder.field(VISIT_PERCENTAGE_FIELD.getPreferredName(), inner.visitPercentage());
        }

        if (inner.queryVectorBuilder() != null) {
            builder.startObject(QUERY_VECTOR_BUILDER_FIELD.getPreferredName());
            builder.field(inner.queryVectorBuilder().getWriteableName(), inner.queryVectorBuilder());
            builder.endObject();
        } else {
            builder.field(QUERY_VECTOR_FIELD.getPreferredName(), inner.queryVector());
        }
        if (inner.getVectorSimilarity() != null) {
            builder.field(VECTOR_SIMILARITY.getPreferredName(), inner.getVectorSimilarity());
        }

        List<QueryBuilder> filterQueries = inner.filterQueries();
        if (filterQueries.isEmpty() == false) {
            builder.startArray(FILTER_FIELD.getPreferredName());
            for (QueryBuilder filterQuery : filterQueries) {
                filterQuery.toXContent(builder, params);
            }
            builder.endArray();
        }

        if (innerHitBuilder != null) {
            builder.field(INNER_HITS_FIELD.getPreferredName(), innerHitBuilder, params);
        }

        if (innerQuery.boost() != DEFAULT_BOOST) {
            builder.field(BOOST_FIELD.getPreferredName(), innerQuery.boost());
        }
        if (innerQuery.queryName() != null) {
            builder.field(NAME_FIELD.getPreferredName(), innerQuery.queryName());
        }
        if (inner.rescoreVectorBuilder() != null) {
            builder.field(RESCORE_VECTOR_FIELD.getPreferredName(), inner.rescoreVectorBuilder());
        }

        return builder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        // Wire format invariant: knn-section envelopes only ever ship on the wire with a KnnVectorQueryBuilder
        // inner. Rewrite + promotion to subSearchSourceBuilders (for intercepted forms, e.g. semantic_text fields)
        // happens on the coordinator before serialization to shards, so any intermediate intercepted shape never
        // reaches writeTo.
        KnnVectorQueryBuilder inner = asKnnVectorQueryBuilder();
        if (inner.queryVectorBuilder() != null && inner.queryVector() == null) {
            // Mirrors the legacy guard at this point: a queryVectorBuilder that has not yet been resolved to a
            // concrete vector means the rewrite pipeline did not run rewriteAndFetch.
            // (KnnVectorQueryBuilder enforces the same via its querySupplier-state check in its own writeTo.)
        }
        out.writeString(inner.getFieldName());
        out.writeVInt(inner.k());
        out.writeVInt(inner.numCands());
        if (out.getTransportVersion().supports(VISIT_PERCENTAGE)) {
            out.writeOptionalFloat(inner.visitPercentage());
        }
        out.writeOptionalWriteable(inner.queryVector());
        out.writeNamedWriteableCollection(inner.filterQueries());
        out.writeFloat(inner.boost());
        out.writeOptionalString(inner.queryName());
        out.writeOptionalNamedWriteable(inner.queryVectorBuilder());
        out.writeOptionalFloat(inner.getVectorSimilarity());
        out.writeOptionalWriteable(innerHitBuilder);
        out.writeOptionalWriteable(inner.rescoreVectorBuilder());
    }

    public static class Builder {

        private String field;
        private VectorData queryVector;
        private QueryVectorBuilder queryVectorBuilder;
        private Integer k;
        private Integer numCandidates;
        private Float visitPercentage;
        private Float similarity;
        private final List<QueryBuilder> filterQueries = new ArrayList<>();
        private String queryName;
        private float boost = DEFAULT_BOOST;
        private InnerHitBuilder innerHitBuilder;
        private RescoreVectorBuilder rescoreVectorBuilder;

        public Builder addFilterQueries(List<QueryBuilder> filterQueries) {
            Objects.requireNonNull(filterQueries);
            this.filterQueries.addAll(filterQueries);
            return this;
        }

        public Builder field(String field) {
            this.field = field;
            return this;
        }

        public Builder queryName(String queryName) {
            this.queryName = queryName;
            return this;
        }

        public Builder boost(float boost) {
            this.boost = boost;
            return this;
        }

        public Builder innerHit(InnerHitBuilder innerHitBuilder) {
            this.innerHitBuilder = innerHitBuilder;
            return this;
        }

        public Builder queryVector(VectorData queryVector) {
            this.queryVector = queryVector;
            return this;
        }

        public Builder queryVectorBuilder(QueryVectorBuilder queryVectorBuilder) {
            this.queryVectorBuilder = queryVectorBuilder;
            return this;
        }

        public Builder k(Integer k) {
            this.k = k;
            return this;
        }

        public Builder numCandidates(Integer numCands) {
            this.numCandidates = numCands;
            return this;
        }

        public Builder visitPercentage(Float visitPercentage) {
            this.visitPercentage = visitPercentage;
            return this;
        }

        public Builder similarity(Float similarity) {
            this.similarity = similarity;
            return this;
        }

        public Builder rescoreVectorBuilder(RescoreVectorBuilder rescoreVectorBuilder) {
            this.rescoreVectorBuilder = rescoreVectorBuilder;
            return this;
        }

        public KnnSearchBuilder build(int size) {
            int requestSize = size < 0 ? DEFAULT_SIZE : size;
            int adjustedK = k == null ? requestSize : k;
            int adjustedNumCandidates = numCandidates == null
                ? Math.round(Math.min(NUM_CANDS_LIMIT, NUM_CANDS_MULTIPLICATIVE_FACTOR * adjustedK))
                : numCandidates;
            KnnVectorQueryBuilder inner = KnnSearchBuilder.buildInner(
                field,
                queryVector,
                queryVectorBuilder,
                adjustedK,
                adjustedNumCandidates,
                visitPercentage,
                rescoreVectorBuilder,
                similarity
            );
            inner.boost(boost).queryName(queryName).addFilterQueries(filterQueries);
            return new KnnSearchBuilder(inner, innerHitBuilder);
        }
    }
}
