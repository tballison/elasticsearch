#!/usr/bin/env bash
# Resets the inference counter, applies cluster settings, runs a search, and prints
# the full response plus the inference call count.
#
# Usage: ./count_calls.sh --mode=<local|ccs> --mrt=<true|false> --batched=<true|false|unset> --field=<semantic|text>
#
# Flags (all optional, defaults shown):
#   --mode=ccs        local | ccs
#   --mrt=true        ccs_minimize_roundtrips (ignored when mode=local)
#   --batched=true    search.batched_query_phase (unset = remove override)
#   --field=semantic  semantic (test-semantic* / body_semantic, inference in play)
#                     | text (test-plain* / body, zero inference involvement)
set -euo pipefail

LOCAL=http://localhost:9200
MOCK=http://localhost:5000

# Defaults
MODE=ccs
MRT=true
BATCHED=true
FIELD=semantic

for arg in "$@"; do
    case "$arg" in
        --mode=*)   MODE="${arg#--mode=}" ;;
        --mrt=*)    MRT="${arg#--mrt=}" ;;
        --batched=*) BATCHED="${arg#--batched=}" ;;
        --field=*)  FIELD="${arg#--field=}" ;;
        *) echo "Unknown argument: $arg" >&2; exit 1 ;;
    esac
done

if [[ "$FIELD" == "text" ]]; then
    LOCAL_INDEX=test-plain
    REMOTE_INDICES="remote-cluster-1:test-plain-node1,remote-cluster-1:test-plain-node2"
    QUERY_FIELD=body
else
    LOCAL_INDEX=test-semantic
    REMOTE_INDICES="remote-cluster-1:test-semantic-node1,remote-cluster-1:test-semantic-node2"
    QUERY_FIELD=body_semantic
fi

if [[ "$MODE" == "local" ]]; then
    INDICES="$LOCAL_INDEX"
else
    INDICES="$LOCAL_INDEX,$REMOTE_INDICES"
fi

echo "=== mode=$MODE mrt=$MRT batched=$BATCHED field=$FIELD ==="

# Apply (or remove) the batched_query_phase cluster setting
if [[ "$BATCHED" == "unset" ]]; then
    BATCHED_BODY='{"persistent":{"search.batched_query_phase":null}}'
else
    BATCHED_BODY="{\"persistent\":{\"search.batched_query_phase\":$BATCHED}}"
fi
echo "--- cluster setting: search.batched_query_phase=$BATCHED"
curl -sf -XPUT "$LOCAL/_cluster/settings" \
    -H 'Content-Type: application/json' \
    -d "$BATCHED_BODY" > /dev/null

# Reset counter
curl -sf -XPOST "$MOCK/reset" > /dev/null

# Build search URL
SEARCH_URL="$LOCAL/$INDICES/_search?ccs_minimize_roundtrips=$MRT"
SEARCH_BODY='{"size":20,"query":{"match":{"'"$QUERY_FIELD"'":"brown fox"}}}'
# Three OR clauses — uncomment to test if each clause triggers a separate inference call per cluster (expect 6 total)
#SEARCH_BODY='{"size":20,"query":{"bool":{"should":[{"match":{"body_semantic":"brown fox"}},{"match":{"body_semantic":"boxing wizards"}},{"match":{"body_semantic":"sphinx quartz"}}]}}}'

echo "--- request: GET $SEARCH_URL"
echo "    body: $SEARCH_BODY"

# Capture timestamp just before the search so docker log capture is bounded
START_TS=$(date +%s)

# Run the search and capture full response (don't use -f so error bodies are visible)
RESPONSE=$(curl -s -XGET "$SEARCH_URL" \
    -H 'Content-Type: application/json' \
    -d "$SEARCH_BODY")

echo "--- response:"
echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"
echo "--- took: $(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('took','?'))" 2>/dev/null)ms"

# Report inference call count
COUNTS=$(curl -sf "$MOCK/count" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['count']} call(s), {d.get('inputs_total', '?')} input string(s)\")")
echo "--- inference calls: $COUNTS"

# Count [CCS-DIAG] transport events from all ES containers
sleep 1  # let container log streams flush before scraping
echo "--- transport events (from docker logs):"
for container in es-local es-remote-1 es-remote-2; do
    docker logs --since "$START_TS" "$container" 2>&1
done | grep '\[CCS-DIAG\]' \
     | sed 's/.*\[CCS-DIAG\] //' \
     | sed 's/ node=.*//' \
     | sort | uniq -c | sort -rn
echo ""
