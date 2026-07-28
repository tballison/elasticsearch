# Demo runbook — in-JVM first, then tika-server

Steps marked **_(optional)_** are the `System.exit` "crash" — include or skip per your presentation.
One profile runs at a time; both ES nodes are on **localhost:9200**, so `./send.sh <doc>` needs no node arg.

## Terminal layout

- **T1 — logs:** run compose in the **foreground** (no `-d`); this window *is* the log stream.
- **T2 — CPU:** `./watch.sh`
- **T3 — drive:** `./setup.sh` then the `./send.sh` commands.

---

## Part 1 — in-JVM (the problem)

> Tika runs *inside* the Elasticsearch process. ES's entitlement sandbox blocks privileged calls, but
> nothing stops a parser from eating CPU or memory.

```sh
# T1 — brings up es-jvm and streams its log; wait for the "started"/green line
docker compose --profile jvm up
# T3
./setup.sh
```

### 1a. CPU hang — the parse outlives the client
```sh
CLIENT_TIMEOUT=8 ./send.sh cpu.xml
```
- Client gives up at 8s; **T2 shows es-jvm pinned at ~100% CPU.** Prove it's the parse:
  `curl -s localhost:9200/_nodes/hot_threads | grep -A6 hangHeavy`
- Reclaim the core: `./reset.sh`

### 1b. _(optional)_ System.exit — ES *does* block privileged calls
```sh
./send.sh exit.xml
```
- `400 … not_entitled_exception … entitlement [exit_v_m]`; **node survives**. Entitlements stop a parser
  from halting the JVM — but that's privileged *calls*, not resource use.

### 1c. OOM — what ES can't stop
```sh
./send.sh oom.xml
```
- Node OOMs and **terminates** (`OutOfMemoryError` → exit 3). In **T1** you see it die and `compose up` exit.
  A single document took the node down.

**End Part 1:** `Ctrl-C` in T1 (if still running), then `docker compose --profile jvm down`.

---

## Part 2 — tika-server (the fix)

> Same documents, extraction offloaded to tika-server, which runs **each parse in a forked JVM**. A bad
> doc crashes only that disposable fork; tika restarts it and the server stays up.

```sh
# T1 — tika + es-server; wait for healthy
docker compose --profile server up
# T3
./setup.sh
```

### 2a. CPU hang — cancelled, server unharmed
```sh
./send.sh cpu.xml
```
- ~10s later ES returns `400 … tika-server returned HTTP status [503]`. In **T1** the forked parser hits the
  progress timeout, crashes, and is restarted. In **T2** tika CPU spikes then drops.

### 2b. OOM — fork dies, server heals
```sh
./send.sh oom.xml
```
- Forked JVM OOMs and crashes (~2s); tika respawns it. es-server logs the `503`. Server never went down.

### 2c. _(optional)_ System.exit — instant fork crash
```sh
./send.sh exit.xml
```
- Fork exits immediately (`status=UNSPECIFIED_CRASH`) and is respawned.

### 2d. Recovery — prove it healed
```sh
./send.sh normal.xml    # HTTP 200 -- fork already back
docker inspect -f 'status={{.State.Status}} restarts={{.RestartCount}}' "$(basename "$PWD")-tika-1"
# -> status=running restarts=0   (only forks cycled, never the server)
```

**End Part 2:** `Ctrl-C` in T1, then `docker compose down`.

---

## The one-line takeaway

Entitlements block privileged operations even in-JVM (`exit.xml`), but not resource exhaustion
(`cpu.xml`, `oom.xml`) — and only tika-server's forked isolation contains a parser that just eats CPU/memory.
