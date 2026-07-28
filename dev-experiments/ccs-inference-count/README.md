# CCS Inference & Batching Investigation Harness

Investigates two questions about cross-cluster search (CCS), related to
issue [#146908](https://github.com/elastic/elasticsearch/issues/146908):

1. **How many query-time inference calls** does a CCS search on `semantic_text`
   make, and which node runs them? (Answer: one per cluster per search, in every
   mrt/batched combination.)
2. **Why does `search.batched_query_phase=true` remove the dramatic mrt=false
   latency regression** — including for queries with *no* inference field?
   (Answer: unbatched mrt=false pays `ceil(shards_per_node / 5)` *sequential*
   WAN round trips per remote node because of the `max_concurrent_shard_requests`
   throttle; batching collapses each node's shards into one request = one round
   trip regardless of shard count. See "Findings" for measured data.)

Branch: `ccs-inference-check` (in-JVM equivalent: `InferenceCallCountCrossClusterSearchIT`).

## Findings (measured 2026-07-27, Docker Desktop on an Apple-silicon MacBook Pro)

Plain `match` query on a plain `text` field — **zero inference involvement** —
CCS with `ccs_minimize_roundtrips=false`, 50ms one-way (~105ms RTT) injected on
the inter-cluster link only, 15 tiny documents total:

| shards/idx | ~shards per remote node | waves = ceil(n/5) | predicted took (445 + (waves−1)×105) | measured, batched=false | measured, batched=true |
|---|---|---|---|---|---|
| 5  | 5  | 1 | 445  | 449, 447 | 442, 453 |
| 10 | 10 | 2 | 550  | 564, 545 | 439, 451 |
| 15 | 15 | 3 | 655  | 643, 638 | 440, 443 |
| 20 | 20 | 4 | 760  | 769, 771 | 443, 446 |
| 30 | 30 | 6 | 970  | 949, 984 | 433, 445 |

Unbatched `took` fits `base + (ceil(shards_per_node/5) − 1) × RTT` almost
exactly; batched stays flat at the ~445ms base regardless of shard count.
At 1000 remote shards (~500/node, 100 waves) the model still holds within 1%:
**10.9–11.0s unbatched vs 0.43–0.77s batched (~15–25×)**. (Batched picks up a
small fixed bump past 128 shards — can_match pre-filter — plus visible per-node
execution cost at high shard counts; still sub-second.)

Additional controls, all measured on this harness:

- **mrt=true**: flat ~115ms (≈1 WAN round trip) at every shard count, in both
  batching modes — the scaling penalty is specific to the mrt=false per-shard
  WAN fan-out. (mrt=false matters because it is forced for PIT, scroll, etc.)
- **Index count is irrelevant**: 100 indices × 10 shards and 2 indices × 500
  shards time identically. Both fan-out layers group by node, never by index
  (`SendingTarget(clusterAlias, nodeId)`); only shards-per-node matters.
- **Clause count is orthogonal**: a 1000-clause bool query (unique tokens per
  clause to defeat clause dedup) adds ~1.2s of per-shard parse compute to BOTH
  modes equally; the batched-vs-unbatched gap stays fixed at waves × RTT.
- `search.batched_query_phase` is **enabled by default since 9.5.0** (#148622;
  the setting was introduced in #121885).

**Mechanism** (`SearchQueryThenFetchAsyncAction.doRun`, ~line 503): unbatched,
the coordinator sends one transport request **per shard**, throttled to
`max_concurrent_shard_requests` (default **5**) concurrent requests per node
(`SearchRequest.java:106`, enforced by `PendingExecutions`); an unbatched node
with N shards therefore costs ceil(N/5) sequential round trips. Batched, shards
are grouped by `(clusterAlias, nodeId)` and sent as **one**
`indices:data/read/search[query][n]` request per node, partially reduced on the
data node. Coordinator-local shards always go per-shard (no network latency).

Note "waves" is emergent, not a barrier: `PendingExecutions` is a per-node
`Semaphore(5)` sliding window (`AbstractSearchAsyncAction.java:926`) — each
response frees its slot and dispatches the next queued shard immediately. Under
uniform RTT (netem) the window degenerates into synchronized batches of 5;
under real-world jitter the chains desynchronize but total time still scales
as ~ceil(N/5) × RTT.

**Ruled out as the mechanism**: inference-call count (the mock counter shows
2 calls — 1 local + 1 remote — in every mrt/batched combination) and the
mrt=false-only `GetInferenceFieldsInternalAction` RPC (fires once regardless
of batching).

**Cross-checks worth running**: (a) `?max_concurrent_shard_requests=30` on an
unbatched high-shard search should collapse the gap without batching;
(b) at 5 shards/node the *semantic* query showed a +126ms gap (634 vs 508) that
the plain query does not (449 vs 442) — likely the duplicated per-shard query
embedding payload (see "Phase bytes" log), a second-order effect.

## What's on this branch (instrumentation, not for upstream)

Java changes — rebuild the Docker image after touching any of these:

- `server/.../action/search/AbstractSearchAsyncAction.java` —
  `[CCS-DIAG] Shard query` (one line per per-shard dispatch, in
  `doPerformPhaseOnShard`) and `[CCS-DIAG] Phase bytes` (per-phase wire-format
  request/response bytes in `executeNextPhase`; **only off-node transport is
  counted**, so it is exactly the network payload; logged only when nonzero).
- `server/.../action/search/SearchQueryThenFetchAsyncAction.java` —
  `[CCS-DIAG] Node-batched query` (one line per per-node batched request, the
  batched-mode replacement for per-shard sends; the single-shard-per-node and
  BwC fallbacks funnel through `performPhaseOnShard` and are counted there).
- `x-pack/plugin/inference/.../action/TransportGetInferenceFieldsInternalAction.java` —
  `[CCS-DIAG] GetInferenceFieldsInternalAction` with `hasInput=` on **every**
  request, including metadata-only ones (`input=null`). Diagnostic: does a
  plain query on plain indices still pay this RPC?
- Pre-existing on the branch: INFO logs in `InferenceQueryUtils`
  (`LocalInferenceAsyncAction.executeInferenceRequest` — fires only on a
  coordinator query rewrite that needs embeddings) and
  `TransportInferenceAction.doInference` (the funnel point every inference
  request passes through). Together they separate "who rewrote the query" from
  "who executed inference":

  | Mode | es-local | remote coordinator |
  |---|---|---|
  | `mrt=true` | both logs | both logs |
  | `mrt=false` | both logs | `TransportInferenceAction` **only** |

Harness files in this directory:

| File | Purpose |
|---|---|
| `docker-compose.yml` | 1-node local cluster + 2-node remote cluster + mock inference server; static IPs for tc filters |
| `mock_inference.py` | mock HuggingFace embedding server; counts calls (`GET :5000/count`, `POST :5000/reset`) |
| `setup.sh` | endpoints, remote-cluster wiring, indices (semantic + plain), documents; `SHARDS=<n>` to size |
| `count_calls.sh` | one measured search: applies settings, resets counters, prints response/took/inference-count/`[CCS-DIAG]` events; `--field=semantic\|text`, `--bool=true` for 3 semantic/plain clauses |
| `wan_latency.sh` | inject/clear netem delay on inter-cluster packets ONLY |
| `sweep_shards.sh` | took vs shard count, batched vs unbatched, on the no-inference plain path; knobs: `NIDX` (remote index count), `CLAUSES` (generated bool clauses), `MRT`, `BOOL` |

## Topology

| Container | Cluster | HTTP port | Static IP | Notes |
|---|---|---|---|---|
| `es-local` | `local-cluster` | 9200 | 172.28.0.10 | single node, CCS coordinator |
| `es-remote-1` | `remote-cluster` | 9201 | 172.28.0.21 | master |
| `es-remote-2` | `remote-cluster` | 9202 | 172.28.0.22 | |
| `mock-inference` | — | 5000 | 172.28.0.5 | mock HF embedding server, counts calls |

The remote cluster is registered on `es-local` as `remote-cluster-1` (sniff mode,
seeds on transport port 9300). Two index families, all `SHARDS` shards each
(default 5), replicas 0:

- **semantic** (inference in play): `test-semantic` (local), `test-semantic-node1`,
  `test-semantic-node2` (remote) — `body` text + `body_semantic` `semantic_text`
  backed by the `mock-hf` endpoint pointing at the mock server.
- **plain** (zero inference): `test-plain` (local), `test-plain-node1`,
  `test-plain-node2` (remote) — `body` text only.

Remote shards are allocated freely across both remote nodes (~SHARDS per node).
Check placement:

```bash
curl "http://localhost:9201/_cat/shards/test-*?v&h=index,shard,state,docs,node"
```

To pin an index to one node add
`"index.routing.allocation.require._name": "es-remote-1"` to its settings in
`setup.sh`.

## Replication, step by step

### 0. Prerequisites

- Docker (Docker Desktop on macOS is what the findings were measured on).
- ~6GB free for the three ES containers (2GB heap each).
- `nicolaka/netshoot` image (pulled automatically on first `wan_latency.sh` use).
- JDK 25 via `JAVA_HOME` for the image build.

### 1. Build the instrumented image

```bash
# from the repo root, on branch ccs-inference-check
./gradlew :distribution:docker:buildAarch64DockerImage     # Apple silicon
# ./gradlew :distribution:docker:buildDockerImage          # x86_64
docker tag elasticsearch:9.6.0-SNAPSHOT elasticsearch:main
```

(Adjust the version tag if `main` has moved past 9.6.0.)

### 2. Start the stack and set it up

```bash
cd dev-experiments/ccs-inference-count
IMAGE=elasticsearch:main docker compose up -d
bash setup.sh                    # waits for health, wires clusters, creates indices
```

`SHARDS=20 bash setup.sh` re-creates all six indices with 20 shards each
(deletes existing first; re-runnable any time).

### 3. Experiment 1 — inference call counting

```bash
./count_calls.sh --mode=local                            # expect 1 inference call
./count_calls.sh --mode=ccs --mrt=true  --batched=true   # expect 2
./count_calls.sh --mode=ccs --mrt=true  --batched=false  # expect 2
./count_calls.sh --mode=ccs --mrt=false --batched=true   # expect 2
./count_calls.sh --mode=ccs --mrt=false --batched=false  # expect 2
```

Invariant: **one inference call per cluster per search, never per shard or per
node** — a per-shard regression would show as ~15 calls. `count_calls.sh` also
prints `took` and the `[CCS-DIAG]` transport events scraped from all three
containers' docker logs.

### 4. Experiment 2 — transport fan-out, batched vs unbatched

With the default 5 shards/index (5 local + 10 remote over 2 nodes), the
`[CCS-DIAG]` section of `count_calls.sh --mode=ccs --mrt=false ...` should show:

| | Shard query | Node-batched query |
|---|---|---|
| `--batched=false` | 15 (5 local + 10 remote) | 0 |
| `--batched=true` | 5 (local only) | 2 (one per remote node) |

Also compare `Phase bytes` requestBytes between the two modes: unbatched
duplicates the full (rewritten) query per shard request; batched serializes it
once per node. For the semantic path the rewritten query contains the KB-scale
embedding, one per semantic clause.

To ask "does a *plain* query still pay the GetInferenceFields RPC?":

```bash
./count_calls.sh --field=text --mrt=false --batched=true
# then look for: GetInferenceFieldsInternalAction ... hasInput=false
```

### 5. Experiment 3 — WAN latency + shard sweep (the headline result)

```bash
./wan_latency.sh set 50       # 50ms one-way => ~100ms RTT, inter-cluster ONLY
./wan_latency.sh ping         # verify: local->remote ~105ms, remote->remote ~0.1ms

./sweep_shards.sh             # default 10 100 1000 10000 shards/index
./sweep_shards.sh 5 25 50     # custom shards-per-index counts
NIDX=100 ./sweep_shards.sh 10 # 100 remote indices x 10 shards (vs NIDX=2 500 — identical)
MRT=true ./sweep_shards.sh    # control: flat ~1 RTT at every count
CLAUSES=1000 ./sweep_shards.sh 500   # generated 1000-clause bool query

./wan_latency.sh clear
```

The sweep creates its own plain indices — `test-plain` (local) plus `NIDX`
remote indices `test-plain-r1..rN` of the given shard count each (no inference
anywhere) — and for each count runs 1 warm-up + 2 measured searches per batched
mode. It automatically raises `cluster.max_shards_per_node` to fit and sets
`action.destructive_requires_name=false` (dev harness) for wildcard cleanup.
Wave prediction: `took ≈ base + (ceil(shards_per_node/5) − 1) × RTT` for
batched=false, flat `base` for batched=true; shards_per_node ≈
shards_per_index × NIDX / 2.

Counts above 1024 shards/index need the `-Des.index.max_number_of_shards=20000`
already present in `docker-compose.yml`'s `ES_JAVA_OPTS` (a compose down/up is
required for it to take effect). Fair warning: 10000/idx = 30k shards on three
2GB-heap containers is far past sizing guidance and may fall over; bump heap or
stop at ~1000/idx.

### Gotchas

- **`docker compose down`/`up` wipes everything**: cluster state (no volumes),
  so re-run `setup.sh`; and the tc qdiscs (they live in the containers' network
  namespaces), so re-run `wan_latency.sh set`. Same after an image rebuild.
- **First search after a topology/latency change is slow** (connection
  re-handshakes). Always warm up once and trust the later runs — the sweep does
  this automatically.
- **`wan_latency.sh` requires the static IPs** pinned in `docker-compose.yml`;
  if you change them, update the script's constants.
- `count_calls.sh --batched=...` sets the `search.batched_query_phase`
  persistent cluster setting on the local cluster; `--batched=unset` removes it.
- The mock counter accumulates across searches; `count_calls.sh` resets it
  before each run, and `setup.sh` resets it after ingest.

## Why the inference log signatures differ (mrt flow summary)

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
    → LocalInferenceAsyncAction.executeInferenceRequest      ← rewrite log fires
      → InferenceQueryUtils.executeInferenceForTaskType (static)
        → InferenceAction
          → TransportInferenceAction.doInference             ← inference log fires

mrt=false (remote receives the GetInferenceFields RPC; no search rewrite):
  TransportGetInferenceFieldsInternalAction.getInferenceResults
    → InferenceQueryUtils.executeInferenceForTaskType (static)  ← same shared helper
      → InferenceAction
        → TransportInferenceAction.doInference               ← only inference log fires
```

Both paths converge on the same static helper; only the caller differs. The
`GetInferenceFieldsInternalAction` RPC is the extra per-query roundtrip that is
unique to mrt=false — but it is constant (1 RTT) and unaffected by batching;
the shard fan-out waves above are what scale with cluster size.
