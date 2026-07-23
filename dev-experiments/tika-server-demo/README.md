# Misbehaving-parser demo: in-JVM vs tika-server

Demonstrates what happens when an `ingest-attachment` parse goes rogue (heavy CPU / effectively
non-terminating), and how the two extraction backends differ in blast radius and cancellability.

## The point

| | in-JVM (`LocalExtractionBackend`) | tika-server (`TikaServerExtractionBackend`) |
|---|---|---|
| Execution | synchronous, on the ingest/write thread | async HTTP (`sendAsync`) |
| Timeout | **none, anywhere** | `ingest.attachment.tika_server.timeout` (default 60s) |
| Runaway parse | **keeps burning CPU on the ES node** even after the client's request returns; a few docs exhaust the write pool | request fails cleanly at timeout; **the server cancels the task**, CPU drops; ES node unaffected |

The star of the demo is the in-JVM side: **nothing stops it.** Even after the client's REST call to
Elasticsearch times out and returns, the node keeps pegging a core — visible in `docker top` / hot_threads.

Why (verified in code): in-JVM makes `AttachmentProcessor` run the synchronous `execute(IngestDocument)`
path on a write thread, calling `TikaImpl.parse` with no timeout; the CPU busy-loop never throws, so nothing
interrupts it. The tika-server path is async with an `HttpRequest.timeout(...)`, so ES gets a clean failure.

## What makes this work

- `MockParser` (from `tika-core-*-tests.jar`) is added to the in-JVM `PARSERS[]` in `TikaImpl` and is on
  the tika-server classpath. It activates only on `application/mock+xml`.
- Detection is automatic: the tests jar ships `custom-mimetypes.xml` at the classpath root mapping the
  `<mock>` root element to `application/mock+xml`.
- The mock document ([`mock-heavy-cpu.xml`](./mock-heavy-cpu.xml)) instructs the parser to burn CPU for
  ~1 hour (`<hang heavy="true" millis="3600000" .../>`).

## Prerequisites

- Elasticsearch built from the `add-tika-server-demo` branch (includes the MockParser wiring).
- A tika-server whose classpath includes `tika-core-*-tests.jar` (so it has MockParser + mock+xml detection).
- The `~/Desktop/stuff/ingest-attachment-demo/` docker-compose (ES + tika-server) is a starting point.

## Run it

### 1. in-JVM mode (no tika-server URL configured)

Start ES with **no** `ingest.attachment.tika_server.url` set → `LocalExtractionBackend`.

```sh
# create the pipeline
curl -s -u elastic-admin:elastic-password -XPUT "localhost:9200/_ingest/pipeline/attach?pretty" \
  -H 'Content-Type: application/json' -d '{
    "processors": [ { "attachment": { "field": "data", "remove_binary": true } } ]
  }'

# index the mock doc; the client request will hang/return, but the node keeps parsing
B64=$(base64 -i mock-heavy-cpu.xml)
curl -s -u elastic-admin:elastic-password -XPUT "localhost:9200/demo/_doc/1?pipeline=attach&pretty" \
  -H 'Content-Type: application/json' -d "{\"data\":\"${B64}\"}"
```

Observe: `docker top <es-container>` (or `GET _nodes/hot_threads`) shows a write thread pegging a core.
It does **not** stop when the curl returns/times out.

### 2. tika-server mode

Start ES with `ingest.attachment.tika_server.url: http://tika-server:9998` and
`ingest.attachment.tika_server.timeout: 90s`. Same pipeline + index request as above.

Observe: after ~90s ES returns a timeout failure for the doc; the tika-server cancels the task and its
CPU drops. The ES node is unaffected throughout.

## Cleanup

```sh
curl -s -u elastic-admin:elastic-password -XDELETE "localhost:9200/demo"
curl -s -u elastic-admin:elastic-password -XDELETE "localhost:9200/_ingest/pipeline/attach"
```
For in-JVM mode, the runaway parse only ends when `millis` elapses — restart the node to reclaim the core.

## Notes

- Avoid `<fakeload>` (spawns threads → needs the fakeload dep + thread entitlements) and `<system_exit>`.
  `<hang heavy>` and `<oom>` need no extra deps or entitlements.
- `<oom/>` (unbounded `int[]` allocation → real `OutOfMemoryError`) is available for an OOM variant, but
  in-JVM it can kill the node — run with a small `-Xmx` if you demo it.
