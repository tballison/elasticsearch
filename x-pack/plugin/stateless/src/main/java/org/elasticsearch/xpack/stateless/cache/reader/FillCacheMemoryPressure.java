/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.stateless.cache.reader;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.concurrent.MemoryBudget;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.telemetry.metric.LongUpDownCounter;
import org.elasticsearch.telemetry.metric.MeterRegistry;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.concurrent.Executor;

/**
 * Bounds heap held by in-flight cache-fill reads on the receive side. A fetched range occupies untracked heap (pooled Netty buffer /
 * SDK buffers) from network arrival until a fill thread writes it to disk; without a bound the network outruns the disk-bound fill
 * pool and exhausts heap.
 *
 * Receive-side counterpart of {@link org.elasticsearch.xpack.stateless.commits.GetVirtualBatchedCompoundCommitChunksPressure}, which
 * releases at send time — exactly when receiver exposure starts.
 *
 * Acquirers queue FIFO rather than being rejected: rejection would fail warming/prefetching in the very overload this exists for.
 * Latency-sensitive paths (cache-miss reads) must bypass; see {@link CacheBlobReaderService}.
 *
 * A queue head unmoved for {@link #STALL_WARN_THRESHOLD} means nothing was released in that period — typically an admitted read whose
 * stream was never drained or closed. WARN'd at most once per period.
 *
 * No shutdown handling: queued listeners must tolerate never completing (all acquirers are speculative fills, for which this is
 * inherent anyway).
 *
 * Thin adapter over {@link MemoryBudget}: all locking, FIFO grant/release, and stall detection
 * logic live in the shared primitive; this class keeps the stateless-specific settings, APM metrics,
 * and log message text.
 */
public class FillCacheMemoryPressure {

    public static final String CURRENT_BYTES_METRIC = "es.fill_cache.memory.current";
    public static final String WAITING_BYTES_METRIC = "es.fill_cache.memory.waiting.current";

    public static final Setting<ByteSizeValue> FILL_BYTES_LIMIT = Setting.memorySizeSetting(
        "stateless.fill_cache.memory.limit",
        "10%",
        Setting.Property.NodeScope
    );

    public static final Setting<TimeValue> STALL_WARN_THRESHOLD = Setting.timeSetting(
        "stateless.fill_cache.memory.stall_warn_threshold",
        TimeValue.timeValueSeconds(60),
        Setting.Property.NodeScope
    );

    private static final Logger logger = LogManager.getLogger(FillCacheMemoryPressure.class);

    private final long fillBytesLimit;
    private final MemoryBudget budget;

    public FillCacheMemoryPressure(Settings settings, MeterRegistry meterRegistry, ThreadPool threadPool) {
        this.fillBytesLimit = FILL_BYTES_LIMIT.get(settings).getBytes();
        final TimeValue stallWarnThreshold = STALL_WARN_THRESHOLD.get(settings);

        final LongUpDownCounter metricCurrentBytes = meterRegistry.registerLongUpDownCounter(
            CURRENT_BYTES_METRIC,
            "Current bytes admitted for in-flight cache-fill reads",
            "bytes"
        );
        final LongUpDownCounter metricWaitingBytes = meterRegistry.registerLongUpDownCounter(
            WAITING_BYTES_METRIC,
            "Bytes of cache-fill reads waiting for memory budget",
            "bytes"
        );

        this.budget = new MemoryBudget(
            "fill_cache",
            fillBytesLimit,
            new NoopCircuitBreaker("fill_cache"), // PoC; production uses a registered child breaker
            threadPool::relativeTimeInMillis,
            threadPool, // ThreadPool implements Scheduler; stall checks run on DIRECT_EXECUTOR_SERVICE
            (noReleaseMillis, headBytes, waiterCount, waitingBytes, currentBytes, limitBytes) -> logger.warn(
                "cache-fill memory budget stalled: no budget released for [{}] while the queue head waits for [{}] bytes; "
                    + "[{}] waiters totaling [{}] bytes; [{}] of [{}] bytes admitted but not yet released — "
                    + "check for admitted reads whose stream was never drained or closed",
                TimeValue.timeValueMillis(noReleaseMillis),
                headBytes,
                waiterCount,
                waitingBytes,
                currentBytes,
                limitBytes
            ),
            stallWarnThreshold,
            metricCurrentBytes::add,
            metricWaitingBytes::add
        );
    }

    /**
     * Acquires {@code bytes} of fill budget. The listener is completed with a {@link Releasable} that must be released exactly once,
     * when the read no longer occupies heap. Completed inline if budget is free and no earlier acquirer is waiting; otherwise queued
     * FIFO and completed on {@code executor} — must be the pool the deferred read is allowed to run on (typically the acquirer's own
     * pool). Requests larger than the whole limit are granted once nothing else is in flight, so they cannot wait forever.
     */
    public void acquire(long bytes, Executor executor, ActionListener<Releasable> listener) {
        budget.acquire(bytes, executor, listener);
    }

    // exposed for tests
    public long getCurrentBytes() {
        return budget.current();
    }

    // exposed for tests
    public int getWaiterCount() {
        return budget.waiterCount();
    }

    /**
     * Releases {@code bytes} back to the fill budget. This is the internal entry point called by
     * the {@link Releasable} returned by {@link #acquire}; it is package-private so that test
     * Javadoc can reference it via {@code {@literal @}link}.
     *
     * <p>Callers that already hold the {@link Releasable} from {@link #acquire} must close it
     * instead of calling this method — calling both causes a double-release.
     */
    void release(long bytes) {
        budget.release(bytes);
    }
}
