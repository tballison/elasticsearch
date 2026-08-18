# Budgeted Queue PoC Notes

## What Was Extracted

`FillCacheMemoryPressure` (283 LOC, x-pack/stateless) contained a self-contained
memory-accounting gate: synchronized budget counter, FIFO waiter queue, iterative
release loop with executor-rejection reclaim, stall detection, and APM metric hooks.

That logic was extracted into:

- **`MemoryBudget`** (343 LOC, server) — the accounting primitive.
  Public surface: `acquire` (DEFER), `tryAcquire` (REJECT), `current()`, `waiting()`,
  `waiterCount()`, `release(long bytes)`.
  Backed by a `CircuitBreaker` (`NoopCircuitBreaker` for this PoC; a real child breaker
  enables parent-hierarchy participation without code changes).
  Queue-agnostic: no references to `BudgetedTaskRunner` or any task type.
  Stall detection uses `org.elasticsearch.threadpool.Scheduler` (the interface `ThreadPool`
  already implements) rather than a custom boolean-returning inner interface.

- High-water marks (added 2026-08-17): `peakCurrentAndReset()` / `peakWaitingAndReset()`
  — max since last read, reset to instantaneous (held bytes stay as floor). Near-miss
  telemetry: sampled gauges miss transients between scrapes. Single-scraper semantics.

- **`BudgetedTaskRunner`** (112 LOC, server) — composes `MemoryBudget` with
  `ThrottledTaskRunner`. Provides `REJECT` and `DEFER` overload policies. The `REJECT`
  path uses a pre-built shared `EsRejectedExecutionException` (stackless by design);
  zero allocation per rejection under a flood.

## Adopter Policy Rationale

| Adopter | Policy | Reason |
|---|---|---|
| `SearchTransportService.buildFreeContextExecutor` | `REJECT` | Free-context RPCs are fire-and-forget; the transport layer already sends an error response via `AbstractRunnable.onRejection`. Queuing failed requests delays a response that will time out anyway. |
| `FillCacheMemoryPressure` | `DEFER` (via `MemoryBudget.acquire`) | Cache-fill warming reads are speculative; dropping them under pressure defeats the purpose. Callers expect FIFO so large reads cannot be overtaken by smaller later requests. DEFER is the original contract; keeping it unchanged lets the unmodified test suite pass as a fidelity gate. |

## Net LOC Accounting

| File | Before | After | Delta |
|---|---|---|---|
| `FillCacheMemoryPressure.java` (x-pack) | 283 | 138 | −145 |
| `MemoryBudget.java` (server, new) | — | 343 | +343 |
| `BudgetedTaskRunner.java` (server, new) | — | 112 | +112 |
| `SearchTransportService.java` (modified) | net +40 | | +40 |
| **Net production delta** | | | **+350** |

LOC recovered across two passes:
- **Tersification** (first pass): removed ~50 comment/Javadoc lines across all files
- **Scheduler interface removal** (second pass): removed the custom `MemoryBudget.Scheduler`
  interface and the boolean-mapping adapter in `FillCacheMemoryPressure` (~16 lines),
  offset by the `neverCancelled()` stub helper needed in `MemoryBudgetTests` (+7 lines);
  net −9 production lines.

## What Resisted Generalization

1. **CircuitBreaker wiring** — production `FillCacheMemoryPressure` should register a real
   child breaker so fill-cache OOM surfaces as a `CircuitBreakingException`. The right parent
   hierarchy and naming are cluster-topology-specific. Left as `NoopCircuitBreaker` with a
   comment; the hook is already in `MemoryBudget.doGrant`.

2. **Stall check executor** — `MemoryBudget` schedules stall checks with
   `EsExecutors.DIRECT_EXECUTOR_SERVICE` (runs on the scheduler thread). This is
   acceptable for the lightweight check (`logger.warn`), but a future production wiring
   could pass `threadPool.generic()` via an additional constructor parameter if needed.

3. **`FillCacheMemoryPressure.release(long bytes)`** — the original class had a private
   `release(long bytes)` referenced in a test Javadoc `{@link}`. After extraction the
   method lives in `MemoryBudget`. Resolution: `MemoryBudget.release(long bytes)` is
   public; `FillCacheMemoryPressure` exposes a package-private wrapper so the unmodified
   test Javadoc compiles without touching the test file.

4. **No DEFER policy in `BudgetedTaskRunner` for `SearchTransportService`** — the transport
   executor uses `REJECT` only. The DEFER path is tested directly in `MemoryBudgetTests`
   and `BudgetedTaskRunnerTests`.
