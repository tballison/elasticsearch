#!/usr/bin/env bash
# Measures CCS mrt=false latency vs shard count using the PLAIN indices
# (no inference anywhere), batched vs unbatched query phase.
#
# For each shard count: recreates test-plain* indices, then for each batched
# mode runs 1 warm-up + 2 measured searches and reports both "took" values.
#
# Run ./wan_latency.sh set <ms> first — without injected latency the round-trip
# waves are invisible on a local bridge.
#
# Usage: ./sweep_shards.sh [shard-count ...]     (default: 5 10 20 30 40 50)
set -euo pipefail

LOCAL=http://localhost:9200
REMOTE=http://localhost:9201
if [[ $# -gt 0 ]]; then
    COUNTS=("$@")
else
    COUNTS=(5 10 20 30 40 50)
fi

INDICES="test-plain,remote-cluster-1:test-plain-node1,remote-cluster-1:test-plain-node2"
SEARCH_BODY='{"size":20,"query":{"match":{"body":"brown fox"}}}'

recreate_plain_indices() {
    local shards=$1
    curl -s -XDELETE "$LOCAL/test-plain" > /dev/null || true
    curl -s -XDELETE "$REMOTE/test-plain-node1,test-plain-node2" > /dev/null || true
    local settings="{\"settings\":{\"number_of_shards\":$shards,\"number_of_replicas\":0},
                     \"mappings\":{\"properties\":{\"body\":{\"type\":\"text\"}}}}"
    curl -sf -XPUT "$LOCAL/test-plain" -H 'Content-Type: application/json' -d "$settings" > /dev/null
    curl -sf -XPUT "$REMOTE/test-plain-node1" -H 'Content-Type: application/json' -d "$settings" > /dev/null
    curl -sf -XPUT "$REMOTE/test-plain-node2" -H 'Content-Type: application/json' -d "$settings" > /dev/null
    for i in 1 2 3 4 5; do
        curl -sf -XPOST "$LOCAL/test-plain/_doc" -H 'Content-Type: application/json' \
            -d '{"body": "a quick brown fox jumps over the lazy dog"}' > /dev/null
        curl -sf -XPOST "$REMOTE/test-plain-node1/_doc" -H 'Content-Type: application/json' \
            -d '{"body": "the five boxing wizards jump quickly over the brown fox"}' > /dev/null
        curl -sf -XPOST "$REMOTE/test-plain-node2/_doc" -H 'Content-Type: application/json' \
            -d '{"body": "pack my box with five dozen brown foxes"}' > /dev/null
    done
    curl -sf -XPOST "$LOCAL/test-plain/_refresh" > /dev/null
    curl -sf -XPOST "$REMOTE/test-plain-node1,test-plain-node2/_refresh" > /dev/null
    curl -sf "$LOCAL/_cluster/health/test-plain?wait_for_status=green&timeout=30s" > /dev/null
    curl -sf "$REMOTE/_cluster/health/test-plain-node1,test-plain-node2?wait_for_status=green&timeout=30s" > /dev/null
}

set_batched() {
    curl -sf -XPUT "$LOCAL/_cluster/settings" -H 'Content-Type: application/json' \
        -d "{\"persistent\":{\"search.batched_query_phase\":$1}}" > /dev/null
}

run_search_took() {
    curl -s -XGET "$LOCAL/$INDICES/_search?ccs_minimize_roundtrips=false" \
        -H 'Content-Type: application/json' -d "$SEARCH_BODY" \
        | python3 -c "import sys,json; print(json.load(sys.stdin).get('took','ERR'))"
}

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
    printf "%-12s %-14s %-22s %-22s\n" "$S" "$((S * 2))" "$TOOK_UNBATCHED" "$TOOK_BATCHED"
done
