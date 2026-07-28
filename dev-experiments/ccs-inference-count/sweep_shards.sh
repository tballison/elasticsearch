#!/usr/bin/env bash
# Measures CCS mrt=false latency vs shard count using PLAIN indices
# (no inference anywhere), batched vs unbatched query phase.
#
# For each shard count: recreates the plain indices (NIDX remote indices of
# <count> shards each, named test-plain-r1..rN, plus a local test-plain), then
# for each batched mode runs 1 warm-up + 2 measured searches and reports both
# "took" values.
#
# Network behavior depends only on total shards per remote node, not index
# count — NIDX lets you demonstrate that (e.g. 100 indices x 10 shards vs
# 2 x 500 should produce identical timings).
#
# Run ./wan_latency.sh set <ms> first — without injected latency the round-trip
# waves are invisible on a local bridge.
#
# Usage: ./sweep_shards.sh [shards-per-index ...] (default: 10 100 1000 10000)
#        NIDX=100 ./sweep_shards.sh 10            100 remote indices x 10 shards
#        MRT=true ./sweep_shards.sh               (default: MRT=false)
#        BOOL=true ./sweep_shards.sh              3-clause bool/should instead of single match
#        CLAUSES=1000 ./sweep_shards.sh           generated N-clause bool/should (overrides BOOL);
#                                                 max_clause_count defaults to 4096
#
# Shards/idx > 1024 needs -Des.index.max_number_of_shards in ES_JAVA_OPTS
# (set in docker-compose.yml; requires compose down/up + setup.sh + wan_latency.sh).
set -euo pipefail

LOCAL=http://localhost:9200
REMOTE=http://localhost:9201
MRT=${MRT:-false}
BOOL=${BOOL:-false}
NIDX=${NIDX:-2}
CLAUSES=${CLAUSES:-0}
if [[ $# -gt 0 ]]; then
    COUNTS=("$@")
else
    COUNTS=(10 100 1000 10000)
fi

# Raise cluster.max_shards_per_node to fit the largest count (remote total is
# shards x NIDX over 2 nodes; allow full lopsidedness), and permit wildcard
# deletes so re-runs can clean up test-plain-r*.
MAX_COUNT=0
for S in "${COUNTS[@]}"; do
    (( S > MAX_COUNT )) && MAX_COUNT=$S
done
SHARD_LIMIT=$(( MAX_COUNT * NIDX * 2 < 1000 ? 1000 : MAX_COUNT * NIDX * 2 ))
for URL in "$LOCAL" "$REMOTE"; do
    curl -sf -XPUT "$URL/_cluster/settings" -H 'Content-Type: application/json' \
        -d "{\"persistent\":{\"cluster.max_shards_per_node\":$SHARD_LIMIT,
             \"action.destructive_requires_name\":false}}" > /dev/null
done

INDICES="test-plain,remote-cluster-1:test-plain-r*"
if (( CLAUSES > 0 )); then
    # N distinct match clauses built from the indexed vocabulary, so they all
    # parse, execute, and (mostly) match real documents
    SEARCH_BODY=$(python3 -c "
import json
n = $CLAUSES
words = ['quick', 'brown', 'fox', 'jumps', 'lazy', 'dog', 'boxing', 'wizards', 'five', 'dozen']
clauses = [
    {'match': {'body': f'{words[i % len(words)]} {words[(i // len(words)) % len(words)]} filler{i}'}}
    for i in range(n)
]
print(json.dumps({'size': 20, 'query': {'bool': {'should': clauses}}}))
")
elif [[ "$BOOL" == "true" ]]; then
    SEARCH_BODY='{"size":20,"query":{"bool":{"should":[{"match":{"body":"brown fox"}},{"match":{"body":"boxing wizards"}},{"match":{"body":"dozen foxes"}}]}}}'
else
    SEARCH_BODY='{"size":20,"query":{"match":{"body":"brown fox"}}}'
fi

recreate_plain_indices() {
    local shards=$1
    curl -s -XDELETE "$LOCAL/test-plain" > /dev/null || true
    curl -s -XDELETE "$REMOTE/test-plain-r*" > /dev/null || true
    local settings="{\"settings\":{\"number_of_shards\":$shards,\"number_of_replicas\":0},
                     \"mappings\":{\"properties\":{\"body\":{\"type\":\"text\"}}}}"
    curl -sf -XPUT "$LOCAL/test-plain" -H 'Content-Type: application/json' -d "$settings" > /dev/null
    for (( i = 1; i <= NIDX; i++ )); do
        curl -sf -XPUT "$REMOTE/test-plain-r$i" -H 'Content-Type: application/json' -d "$settings" > /dev/null
    done
    # a handful of docs so the query has hits; doc count is irrelevant to the measurement
    for i in 1 2 3 4 5; do
        curl -sf -XPOST "$LOCAL/test-plain/_doc" -H 'Content-Type: application/json' \
            -d '{"body": "a quick brown fox jumps over the lazy dog"}' > /dev/null
        curl -sf -XPOST "$REMOTE/test-plain-r1/_doc" -H 'Content-Type: application/json' \
            -d '{"body": "the five boxing wizards jump quickly over the brown fox"}' > /dev/null
    done
    curl -sf -XPOST "$LOCAL/test-plain/_refresh" > /dev/null
    curl -sf -XPOST "$REMOTE/test-plain-r*/_refresh" > /dev/null
    # allocation time scales with total shard count; give big sweeps a longer green-wait
    local timeout=$(( 30 + shards * NIDX / 5 ))s
    curl -sf "$LOCAL/_cluster/health/test-plain?wait_for_status=green&timeout=$timeout" > /dev/null
    curl -sf "$REMOTE/_cluster/health/test-plain-r*?wait_for_status=green&timeout=$timeout" > /dev/null
}

set_batched() {
    curl -sf -XPUT "$LOCAL/_cluster/settings" -H 'Content-Type: application/json' \
        -d "{\"persistent\":{\"search.batched_query_phase\":$1}}" > /dev/null
}

run_search_took() {
    curl -s -XGET "$LOCAL/$INDICES/_search?ccs_minimize_roundtrips=$MRT" \
        -H 'Content-Type: application/json' -d "$SEARCH_BODY" \
        | python3 -c "import sys,json; print(json.load(sys.stdin).get('took','ERR'))"
}

echo "mrt=$MRT bool=$BOOL clauses=$CLAUSES remote_indices=$NIDX query_bytes=${#SEARCH_BODY}"
printf "%-12s %-14s %-22s %-22s\n" "shards/idx" "remote_shards" "batched=false took(ms)" "batched=true took(ms)"
for S in "${COUNTS[@]}"; do
    recreate_plain_indices "$S" >&2
    TOOK_UNBATCHED=""
    TOOK_BATCHED=""
    for B in false true; do
        set_batched "$B"
        run_search_took > /dev/null            # warm-up (connections, caches)
        T1=$(run_search_took)
        T2=$(run_search_took)
        if [[ "$B" == "false" ]]; then TOOK_UNBATCHED="$T1, $T2"; else TOOK_BATCHED="$T1, $T2"; fi
    done
    printf "%-12s %-14s %-22s %-22s\n" "$S" "$((S * NIDX))" "$TOOK_UNBATCHED" "$TOOK_BATCHED"
done
