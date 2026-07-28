# Misbehaving-parser demo: in-JVM vs tika-server

Shows what happens when an `ingest-attachment` parse goes rogue (heavy CPU / effectively
non-terminating), and how the two extraction backends differ in blast radius and cancellability.

## The point

| | in-JVM (`--profile jvm`) | tika-server (`--profile server`) |
|---|---|---|
| Backend | `LocalExtractionBackend` | `TikaServerExtractionBackend` |
| Selected by | `ingest.attachment.tika_server.url` **absent** | `...url: http://tika:9998` |
| Execution | synchronous, on the ingest/write thread | async HTTP; parse runs in tika's forked JVM |
| Timeout | **none** | tika `progressTimeoutMillis` 10s (a no-progress CPU hang trips this before `totalTaskTimeoutMillis` 20s); ES req timeout 90s is a backstop |
| Runaway parse | **keeps burning CPU on the ES node**, even after the client gives up | tika **kills** the forked parse at ~10s and returns `503 {"status":"TIMEOUT"}`; ES node unaffected |

> Verified end-to-end: firing at the server node returns `400 … tika-server returned HTTP status [503]` at
> ~11s (tika's 10s progress timeout + overhead); es-server + tika return to idle. Adjust the timeout in
> `tika-config.json` (`timeout-limits`).

The backend is a **node-level setting**, fixed at startup — you can't switch it per pipeline. So the demo
uses two compose **profiles** with an identical pipeline; only the node config differs. Run one at a time
(both ES nodes bind `:9200`).

## Layout

```
docker-compose.yml   profiles: `jvm` (es-jvm) / `server` (tika + es-server); both ES on :9200, tika :9998
.env                 ES_IMAGE / STACK_VERSION / TIKA_VERSION
es-jvm.yml           elasticsearch.yml WITHOUT tika_server.url  -> in-JVM
es-server.yml        elasticsearch.yml WITH tika_server.url + 90s timeout
tika-config.json     forked parsers + 10s progress / 20s task timeout (this is what cancels)
cpu/oom/exit/normal.xml  sample <mock> documents (see "Sample documents")
send.sh              send a doc to the running node:  ./send.sh <doc.xml>
setup/watch/reset.sh pipeline + observation helpers
demo.md              narrated runbook (in-JVM first, then tika-server)
```

## Prerequisites

1. **Build the ES image from THIS branch** (it carries the tika-server backend + the `MockParser`
   wiring in `TikaImpl`; the published snapshot does not). From the repo root:
   ```sh
   ./gradlew :distribution:docker:buildAarch64DockerImage   # Apple Silicon / aarch64
   ./gradlew :distribution:docker:buildDockerImage          # x86_64
   ```
   Tags `docker.elastic.co/elasticsearch/elasticsearch:9.6.0-SNAPSHOT` into the local daemon (see `.env`).
2. **tika-server MockParser** — the compose mounts `tika-core-*-tests.jar` (from `~/.m2`) into the tika
   container; the `-full` image's entrypoint already has `/tika-extras/*` on its classpath, so MockParser
   and `application/mock+xml` detection load automatically (verified: `/parsers` and `/mime-types` list them).

**Gotcha (both backends):** the mock doc MUST start with `<?xml version="1.0" encoding="UTF-8"?>`.
Tika only runs root-XML detection (which maps `<mock>` → `application/mock+xml`) once it sees the bytes as
XML; without the declaration the doc is detected as `text/plain` and MockParser never runs. All the sample
`*.xml` docs include it.

## Run

> Presenting? Read **[`demo.md`](./demo.md)** — a narrated, read-straight-down walkthrough (in-JVM first,
> then tika-server; the `System.exit` "crash" steps are marked optional).

Run **one profile at a time, in the foreground** so that window streams the logs:
```sh
docker compose --profile jvm up       # Part 1: in-JVM node       -> localhost:9200
#   ... or ...
docker compose --profile server up    # Part 2: tika + es-server  -> localhost:9200  (tika :9998)
```
Then, in another terminal once the node is healthy:
```sh
./setup.sh               # create the `attach` pipeline (run after each profile comes up)
```

Send documents and observe:

| Script | What it does |
|---|---|
| `./send.sh <doc.xml>` | send a mock doc to the running node (:9200). `CLIENT_TIMEOUT=N` changes how long the client waits |
| `./watch.sh`      | live `docker stats` for the demo's running containers (own terminal) |
| `./reset.sh`      | restart es-jvm to kill a runaway CPU parse and reclaim the core |

### Sample documents

| Doc | Action | On `server` (forked) | On `jvm` (in-process) |
|---|---|---|---|
| `cpu.xml`    | `<hang heavy>` ~1h CPU busy-loop | fork cancelled ~10s (`TIMEOUT`) | pins a core forever — **not** protected |
| `oom.xml`    | `<oom/>` unbounded allocation    | forked JVM OOMs, crashes, respawns | real OOM in the ES heap — **not** protected (can down the node) |
| `exit.xml`   | `<system_exit/>`                 | fork exits instantly, respawns | **blocked** by entitlement `exit_v_m` — node survives |
| `normal.xml` | `<write>` a line                 | parses fine (`HTTP 200`)       | parses fine |

> ES entitlements block *privileged* operations (`System.exit`, exec, file/network) even in-JVM — so
> `exit.xml` can't halt the node. They do **not** police *resource exhaustion*: `cpu.xml` and `oom.xml`
> still take the node hostage. That gap is the case for tika-server's forked isolation.

### Watch tika contain the crash (default forked, safe behavior)

tika-server runs each parse in a **forked JVM** (the `pipes` block in `tika-config.json`). A runaway
document crashes only that disposable fork; tika restarts it and the **server container never goes down**.
That's the containment win vs. the in-JVM node, where the same document takes the ES process itself hostage.

With `docker compose --profile server up` running in the foreground (its logs stream in that window),
send a runaway doc from another terminal:
```sh
./send.sh cpu.xml     # or oom.xml / exit.xml
```
In the **compose window** (~10s after firing) the forked parser crashes and is restarted — the server stays up:
```
PipesClient clientId=0: progress timeout: ... limit=10000ms
PerClientServerManager clientId=0: marking server for restart
PipesClient clientId=0: crash id=... status=TIMEOUT
PipesParsingHelper Parse process crashed: TIMEOUT
[fork] PipesServer ... exiting: 17
```
The es-server line `tika-server returned HTTP status [503]` also shows. Run `./send.sh normal.xml` right
after and it parses fine — the fork has already respawned. Throughout, `docker inspect` shows the tika
container `restarts=0`: only the fork cycled, never the server.

_Verified: `exit.xml` and `cpu.xml` both crash only the fork (`process exited with code 1` /
`status=TIMEOUT`); container stays `running`, and the next document parses (`HTTP 200`)._

**The beat that lands:** under `--profile jvm` with `./watch.sh` running, `CLIENT_TIMEOUT=8 ./send.sh cpu.xml`.
The client gives up at 8s, but es-jvm stays at ~100% CPU (`_nodes/hot_threads` still in `MockParser.hangHeavy`).
Switch to `--profile server` and the same doc is cancelled at ~10s, ES fails cleanly, node calm.

## Cleanup

```sh
docker compose down
```

## Notes

- The sample docs use `<hang heavy>`, `<oom/>`, `<system_exit/>`, `<write>` — none need extra deps. On the
  `server` node the forked JVM absorbs all of them safely. On the **in-JVM** node, `oom.xml`/`exit.xml` hit
  the ES process directly (real OOM / `System.exit`) and can take the node down — keep those on `server`
  unless you're deliberately showing the in-JVM blast radius.
- Avoid `<fakeload>` (spawns threads → needs the fakeload dep + thread entitlements); not used here.
