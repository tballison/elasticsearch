# CCS Inference Call Counting Harness

Counts query-time inference calls during cross-cluster search (CCS) on `semantic_text`
fields, and shows **which node** ran inference and **whether it did a full query rewrite**.

Related: issue #146908, branch `ccs-inference-check` (in-JVM equivalent:
`InferenceCallCountCrossClusterSearchIT`).

## Topology

| Container | Cluster | HTTP port | Notes |
|---|---|---|---|
| `es-local` | `local-cluster` | 9200 | single node, CCS coordinator |
| `es-remote-1` | `remote-cluster` | 9201 | master |
| `es-remote-2` | `remote-cluster` | 9202 | |
| `mock-inference` | — | 5000 | mock HuggingFace embedding server, counts calls |

The remote cluster is registered on `es-local` as `remote-cluster-1`.

Indices (all mapped with a `body_semantic` `semantic_text` field backed by the
`mock-hf` endpoint, which points at the mock server):

- `test-semantic` — local, 5 shards
- `test-semantic-node1` — remote, 5 shards
- `test-semantic-node2` — remote, 5 shards

Remote shards are allocated freely across both remote nodes. To pin an index to
one node (so `_index` in hits proves which node served them), add
`"index.routing.allocation.require._name": "es-remote-1"` to its settings in
`setup.sh` (a comment there marks the spot). Check actual placement with:

```bash
curl "http://localhost:9201/_cat/shards/test-semantic-*?v&h=index,shard,state,docs,node"
```

## Running

```bash
# Build an image with the instrumentation (see "Logging" below), tag it "main"
./gradlew :distribution:docker:buildAarch64DockerImage
docker tag elasticsearch:9.6.0-SNAPSHOT elasticsearch:main

# Terminal 1: start the stack (foreground, so you can watch the logs)
IMAGE=elasticsearch:main docker compose up

# Terminal 2: once healthy
bash setup.sh

./count_calls.sh --mode=local                          # expect 1
./count_calls.sh --mode=ccs --mrt=true  --batched=true  # expect 2
./count_calls.sh --mode=ccs --mrt=true  --batched=false # expect 2
./count_calls.sh --mode=ccs --mrt=false --batched=true  # expect 2
./count_calls.sh --mode=ccs --mrt=false --batched=false # expect 2
```

`count_calls.sh` applies `search.batched_query_phase`, resets the mock's counter,
runs the search, prints the full response and the inference call count.
`setup.sh` resets the counter at the end so ingest-time calls don't pollute the
first measurement.

Mock server API: `GET :5000/count`, `POST :5000/reset`, `GET :5000/health`.

## Invariant

**One inference call per cluster per search, never per shard or per node.**
15 shards across 3 nodes still produce exactly 2 calls for a CCS query
(1 local + 1 remote), in every mrt/batched combination. A per-shard regression
would show up as ~15 calls.

## Logging instrumentation

Two INFO logs (on this branch only — not for upstream):

1. `InferenceQueryUtils` — in `LocalInferenceAsyncAction.executeInferenceRequest`.
   Fires only when a node runs a **coordinator query rewrite** that needs embeddings.
2. `TransportInferenceAction.doInference` — the funnel point every inference
   request passes through, regardless of what triggered it.

The pair separates "who rewrote the query" from "who executed inference":

| Mode | es-local | remote coordinator |
|---|---|---|
| `mrt=true` | both logs | both logs |
| `mrt=false` | both logs | `TransportInferenceAction` **only** |

## Why the log signatures differ (mrt flow summary)

**mrt=true**: the remote cluster's coordinator receives the full search request and
independently runs the whole rewrite (including embedding the query text) —
`LocalInferenceAsyncAction` fires there with `clusterAlias=[remote-cluster-1]`.

**mrt=false**: the remote never rewrites. The local coordinator sends
`GetInferenceFieldsInternalAction` to the remote **with the query text in the
request**. The handler (`TransportGetInferenceFieldsInternalAction`) resolves
inference field metadata *and* runs the embedding in the same RPC, calling the
shared static helper `InferenceQueryUtils.executeInferenceForTaskType` directly —
bypassing `LocalInferenceAsyncAction`. The local coordinator then rewrites the
query with the remote's embedding baked in and fans out per-shard requests that
need **zero** further inference.

The two call chains on the remote coordinator:

```
mrt=true  (remote receives a full search request and rewrites it):
  search rewrite phase
    → LocalInferenceAsyncAction.executeInferenceRequest      ← log #1 fires
      → InferenceQueryUtils.executeInferenceForTaskType (static)
        → InferenceAction
          → TransportInferenceAction.doInference             ← log #2 fires

mrt=false (remote receives the GetInferenceFields RPC; no search rewrite):
  TransportGetInferenceFieldsInternalAction.getInferenceResults
    → InferenceQueryUtils.executeInferenceForTaskType (static)  ← same shared helper
      → InferenceAction
        → TransportInferenceAction.doInference               ← only log #2 fires
```

Both paths converge on the same static helper; only the caller differs. The
`GetInferenceFieldsInternalAction` RPC is also the extra per-query roundtrip at
the center of the #146908 latency regression.
