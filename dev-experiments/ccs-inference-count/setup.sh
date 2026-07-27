#!/usr/bin/env bash
# Sets up both clusters with a mock HuggingFace inference endpoint and semantic_text indices.
# Remote cluster gets two indices whose shards spread across both remote nodes (see the
# comment at index creation for how to pin an index to a single node instead).
# Run after `docker compose up` and all healthchecks pass.
set -euo pipefail

LOCAL=http://localhost:9200
REMOTE=http://localhost:9201
REMOTE2=http://localhost:9202
MOCK=http://localhost:5000

INFERENCE_ID=mock-hf
LOCAL_INDEX=test-semantic
REMOTE_INDEX1=test-semantic-node1
REMOTE_INDEX2=test-semantic-node2
PLAIN_LOCAL_INDEX=test-plain
PLAIN_REMOTE_INDEX1=test-plain-node1
PLAIN_REMOTE_INDEX2=test-plain-node2

# Shards per index (semantic and plain alike). Re-running setup.sh with a new
# value deletes and recreates the indices.
SHARDS=${SHARDS:-5}

wait_green() {
    local url=$1 name=$2
    echo "Waiting for $name to be green..."
    until curl -sf "$url/_cluster/health?wait_for_status=green&timeout=5s" > /dev/null; do
        sleep 2
    done
    echo "$name is green."
}

wait_green "$LOCAL"  "es-local"
wait_green "$REMOTE" "es-remote-1"

echo "Waiting for es-remote-2 to join the remote cluster..."
until [ "$(curl -sf "$REMOTE/_cat/nodes?h=name" | wc -l | tr -d ' ')" = "2" ]; do
    sleep 2
done
echo "Remote cluster has 2 nodes."

# Register mock inference endpoint on both clusters
for URL in "$LOCAL" "$REMOTE"; do
    echo "Registering inference endpoint on $URL..."
    curl -sf -XPUT "$URL/_inference/text_embedding/$INFERENCE_ID" \
        -H 'Content-Type: application/json' -d "{
          \"service\": \"hugging_face\",
          \"service_settings\": {
            \"url\": \"http://mock-inference:5000/embed\",
            \"api_key\": \"fake\"
          }
        }" > /dev/null
    echo " done."
done

# Wire local cluster to remote cluster using seeds (transport port 9300)
echo "Configuring remote cluster connection..."
curl -sf -XPUT "$LOCAL/_cluster/settings" \
    -H 'Content-Type: application/json' -d '{
      "persistent": {
        "cluster.remote.remote-cluster-1.seeds": ["es-remote-1:9300", "es-remote-2:9300"]
      }
    }' > /dev/null

echo "Waiting for remote cluster to be connected..."
until curl -sf "$LOCAL/_remote/info" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d.get('remote-cluster-1',{}).get('connected') == True" 2>/dev/null; do
    sleep 2
done
echo "Remote cluster connected."

# Shared mapping used by all indices
MAPPINGS='{
  "properties": {
    "body": { "type": "text" },
    "body_semantic": {
      "type": "semantic_text",
      "inference_id": "'"$INFERENCE_ID"'"
    }
  }
}'

# Plain mapping (no semantic_text, no inference anywhere in the path)
PLAIN_MAPPINGS='{
  "properties": {
    "body": { "type": "text" }
  }
}'

# Delete any existing test indices so setup.sh can be re-run with a new SHARDS value
echo "Deleting existing test indices (if any)..."
curl -s -XDELETE "$LOCAL/$LOCAL_INDEX,$PLAIN_LOCAL_INDEX" > /dev/null || true
curl -s -XDELETE "$REMOTE/$REMOTE_INDEX1,$REMOTE_INDEX2,$PLAIN_REMOTE_INDEX1,$PLAIN_REMOTE_INDEX2" > /dev/null || true
echo " done."

# Local index: SHARDS shards on the single local node
echo "Creating $LOCAL_INDEX on es-local ($SHARDS shards)..."
curl -sf -XPUT "$LOCAL/$LOCAL_INDEX" \
    -H 'Content-Type: application/json' -d "{
      \"settings\": { \"number_of_shards\": $SHARDS, \"number_of_replicas\": 0 },
      \"mappings\": $MAPPINGS
    }" > /dev/null
echo " done."

# Remote indices: 5 shards each, allocated freely across both remote nodes.
# To pin an index to a single node (so hits' _index proves which node served them),
# add this line to the settings block of the curl below:
#   \"index.routing.allocation.require._name\": \"es-remote-1\",   (or es-remote-2)
# Check actual shard placement with:
#   curl "$REMOTE/_cat/shards/test-semantic-*?v&h=index,shard,state,docs,node"

echo "Creating $REMOTE_INDEX1 on remote cluster..."
curl -sf -XPUT "$REMOTE/$REMOTE_INDEX1" \
    -H 'Content-Type: application/json' -d "{
      \"settings\": {
        \"number_of_shards\": $SHARDS,
        \"number_of_replicas\": 0
      },
      \"mappings\": $MAPPINGS
    }" > /dev/null
echo " done."

echo "Creating $REMOTE_INDEX2 on remote cluster..."
curl -sf -XPUT "$REMOTE/$REMOTE_INDEX2" \
    -H 'Content-Type: application/json' -d "{
      \"settings\": {
        \"number_of_shards\": $SHARDS,
        \"number_of_replicas\": 0
      },
      \"mappings\": $MAPPINGS
    }" > /dev/null
echo " done."

# Plain indices: identical topology, zero inference involvement
echo "Creating plain (no-inference) indices ($SHARDS shards each)..."
curl -sf -XPUT "$LOCAL/$PLAIN_LOCAL_INDEX" \
    -H 'Content-Type: application/json' -d "{
      \"settings\": { \"number_of_shards\": $SHARDS, \"number_of_replicas\": 0 },
      \"mappings\": $PLAIN_MAPPINGS
    }" > /dev/null
for IDX in "$PLAIN_REMOTE_INDEX1" "$PLAIN_REMOTE_INDEX2"; do
    curl -sf -XPUT "$REMOTE/$IDX" \
        -H 'Content-Type: application/json' -d "{
          \"settings\": { \"number_of_shards\": $SHARDS, \"number_of_replicas\": 0 },
          \"mappings\": $PLAIN_MAPPINGS
        }" > /dev/null
done
echo " done."

SENTENCES=(
    "a quick brown fox jumps over the lazy dog"
    "the five boxing wizards jump quickly"
    "pack my box with five dozen liquor jugs"
    "how vexingly dumb lazy sphinxes give"
    "the job requires extra pluck and zeal from every young wage earner"
    "sphinx of black quartz judge my vow"
    "two driven jocks help fax my big quiz"
    "five quacking zephyrs jolt my wax bed"
    "the quick onyx goblin jumps over the lazy dwarf"
    "jackdaws love my big sphinx of quartz"
)

# Local: 5 docs
echo "Indexing 5 documents into $LOCAL_INDEX..."
for i in 0 1 2 3 4; do
    curl -sf -XPOST "$LOCAL/$LOCAL_INDEX/_doc?refresh=false" \
        -H 'Content-Type: application/json' \
        -d "{\"body\": \"${SENTENCES[$i]}\", \"body_semantic\": \"${SENTENCES[$i]}\"}" > /dev/null
done
curl -sf -XPOST "$LOCAL/$LOCAL_INDEX/_refresh" > /dev/null
echo " done."

# Remote node 1: 5 docs
echo "Indexing 5 documents into $REMOTE_INDEX1 (es-remote-1)..."
for i in 0 1 2 3 4; do
    curl -sf -XPOST "$REMOTE/$REMOTE_INDEX1/_doc?refresh=false" \
        -H 'Content-Type: application/json' \
        -d "{\"body\": \"${SENTENCES[$i]}\", \"body_semantic\": \"${SENTENCES[$i]}\"}" > /dev/null
done
curl -sf -XPOST "$REMOTE/$REMOTE_INDEX1/_refresh" > /dev/null
echo " done."

# Remote node 2: 5 docs
echo "Indexing 5 documents into $REMOTE_INDEX2 (es-remote-2)..."
for i in 5 6 7 8 9; do
    curl -sf -XPOST "$REMOTE/$REMOTE_INDEX2/_doc?refresh=false" \
        -H 'Content-Type: application/json' \
        -d "{\"body\": \"${SENTENCES[$i]}\", \"body_semantic\": \"${SENTENCES[$i]}\"}" > /dev/null
done
curl -sf -XPOST "$REMOTE/$REMOTE_INDEX2/_refresh" > /dev/null
echo " done."

# Plain indices: 5 docs each (body only — no inference at ingest)
echo "Indexing documents into plain indices..."
for i in 0 1 2 3 4; do
    curl -sf -XPOST "$LOCAL/$PLAIN_LOCAL_INDEX/_doc?refresh=false" \
        -H 'Content-Type: application/json' \
        -d "{\"body\": \"${SENTENCES[$i]}\"}" > /dev/null
    curl -sf -XPOST "$REMOTE/$PLAIN_REMOTE_INDEX1/_doc?refresh=false" \
        -H 'Content-Type: application/json' \
        -d "{\"body\": \"${SENTENCES[$i]}\"}" > /dev/null
    curl -sf -XPOST "$REMOTE/$PLAIN_REMOTE_INDEX2/_doc?refresh=false" \
        -H 'Content-Type: application/json' \
        -d "{\"body\": \"${SENTENCES[$((i + 5))]}\"}" > /dev/null
done
curl -sf -XPOST "$LOCAL/$PLAIN_LOCAL_INDEX/_refresh" > /dev/null
curl -sf -XPOST "$REMOTE/$PLAIN_REMOTE_INDEX1,$PLAIN_REMOTE_INDEX2/_refresh" > /dev/null
echo " done."

# Report ingest-time inference calls, then reset so they don't pollute query tests
INGEST_COUNT=$(curl -sf "$MOCK/count" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['count']} call(s), {d.get('inputs_total', '?')} input string(s)\")")
echo "Inference calls during setup + indexing: $INGEST_COUNT"
echo "Resetting inference call counter..."
curl -sf -XPOST "$MOCK/reset" > /dev/null
echo " done."

echo ""
echo "Setup complete."
echo "Local index:     $LOCAL_INDEX  (es-local)"
echo "Remote indices:  $REMOTE_INDEX1, $REMOTE_INDEX2  (shards across both remote nodes)"
echo "Shard placement: curl \"$REMOTE/_cat/shards/test-semantic-*?v&h=index,shard,state,docs,node\""
