/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.common.util.concurrent;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.threadpool.Scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Memory-accounting admission gate that bounds heap held by in-flight work.
 *
 * <p>Two policies:
 * <ul>
 *   <li>{@link #acquire} — DEFER: grant inline when budget allows and no earlier waiter is
 *       queued; otherwise enqueue FIFO on the caller-supplied executor. Requests larger than
 *       {@code limitBytes} are granted when {@code current == 0} so they cannot wait forever.
 *   <li>{@link #tryAcquire} — REJECT: grant iff it fits and no DEFER waiter is ahead;
 *       returns {@code null} otherwise. Never queues.
 * </ul>
 *
 * <p>Backed by a {@link CircuitBreaker}: normal grants via
 * {@link CircuitBreaker#addEstimateBytesAndMaybeBreak}; a {@link CircuitBreakingException}
 * is treated as does-not-fit. Oversized-when-idle grants and all releases use
 * {@link CircuitBreaker#addWithoutBreaking}. Use {@link org.elasticsearch.common.breaker.NoopCircuitBreaker}
 * to disable breaker enforcement while keeping the own-limit check.
 *
 * <p>Stall detection: when {@link Scheduler} and {@link StallListener} are both non-null,
 * the listener fires when no bytes have been released for {@code stallThreshold} while
 * waiters exist. Tracks time-since-last-release (not waiter enqueue age) to avoid false
 * alarms when releases happen but the head request is too large to admit.
 */
public class MemoryBudget {

    /** Called when no bytes have been released for {@code stallThreshold} while waiters exist. */
    @FunctionalInterface
    public interface StallListener {
        /**
         * @param noReleaseMillis elapsed ms since last release (or construction)
         * @param headBytes       bytes requested by the FIFO queue head
         * @param waiterCount     number of queued waiters
         * @param waitingBytes    total bytes of all queued waiters
         * @param currentBytes    bytes currently admitted but not released
         * @param limitBytes      configured own limit
         */
        void onStall(long noReleaseMillis, long headBytes, int waiterCount, long waitingBytes, long currentBytes, long limitBytes);
    }

    private final String name;
    private final long limitBytes;
    private final CircuitBreaker ledger;
    private final LongSupplier relativeTimeMillis;
    @Nullable
    private final Scheduler scheduler;
    @Nullable
    private final StallListener stallListener;
    private final long stallThresholdMillis;
    @Nullable
    private final LongConsumer currentBytesHook;
    @Nullable
    private final LongConsumer waitingBytesHook;

    private final Object mutex = new Object();
    // volatile so current() / waiting() skip the lock for monitoring reads
    private volatile long currentBytes = 0;
    private volatile long waitingBytes = 0;
    private long lastReleaseMillis;
    private boolean stallCheckScheduled = false;
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();

    private record Waiter(long bytes, Executor executor, ActionListener<Releasable> listener) {}

    /**
     * @param limitBytes       own admission limit; the breaker is an independent second gate
     * @param ledger           accounting backend; both this limit and the breaker must allow a grant
     * @param scheduler        {@link org.elasticsearch.threadpool.Scheduler} for stall checks;
     *                         {@code null} (or {@code null} stallListener) disables stall detection
     * @param stallThreshold   idle duration without a release (while waiters exist) before firing the listener
     * @param currentBytesHook receives the signed byte delta on every admitted-byte change; drives APM counters
     * @param waitingBytesHook receives the signed byte delta on every waiting-byte change; drives APM counters
     */
    public MemoryBudget(
        String name,
        long limitBytes,
        CircuitBreaker ledger,
        LongSupplier relativeTimeMillis,
        @Nullable Scheduler scheduler,
        @Nullable StallListener stallListener,
        TimeValue stallThreshold,
        @Nullable LongConsumer currentBytesHook,
        @Nullable LongConsumer waitingBytesHook
    ) {
        this.name = name;
        this.limitBytes = limitBytes;
        this.ledger = ledger;
        this.relativeTimeMillis = relativeTimeMillis;
        this.scheduler = (scheduler != null && stallListener != null) ? scheduler : null;
        this.stallListener = (scheduler != null && stallListener != null) ? stallListener : null;
        this.stallThresholdMillis = stallThreshold.millis();
        this.currentBytesHook = currentBytesHook;
        this.waitingBytesHook = waitingBytesHook;
        this.lastReleaseMillis = relativeTimeMillis.getAsLong();
    }

    /**
     * DEFER: grants {@code bytes} inline if budget allows and no earlier waiter is queued;
     * otherwise enqueues FIFO and completes {@code listener} on {@code executor} when admitted.
     * {@code executor} must be the pool the deferred work is allowed to run on.
     * A {@link CircuitBreakingException} from the ledger is treated as does-not-fit (queues, does not propagate).
     */
    public void acquire(long bytes, Executor executor, ActionListener<Releasable> listener) {
        assert bytes > 0 : "acquiring non-positive [" + bytes + "] bytes from budget [" + name + "]";
        final boolean queued;
        synchronized (mutex) {
            if (waiters.isEmpty() && fits(bytes) && doGrant(bytes)) {
                queued = false;
            } else {
                waiters.addLast(new Waiter(bytes, executor, listener));
                waitingBytes += bytes;
                if (waitingBytesHook != null) {
                    waitingBytesHook.accept(bytes);
                }
                queued = true;
                if (stallCheckScheduled == false && scheduler != null) {
                    stallCheckScheduled = tryScheduleStallCheckLocked(stallThresholdMillis);
                }
            }
        }
        if (queued) {
            return;
        }
        listener.onResponse(releasableFor(bytes));
    }

    /**
     * REJECT: grants {@code bytes} iff they fit (own limit and breaker) and no DEFER waiter
     * is queued ahead. Returns {@code null} on over-budget, breaker trip, or any queued waiter.
     */
    @Nullable
    public Releasable tryAcquire(long bytes) {
        assert bytes > 0 : "acquiring non-positive [" + bytes + "] bytes from budget [" + name + "]";
        synchronized (mutex) {
            if (waiters.isEmpty() && fits(bytes) && doGrant(bytes)) {
                return releasableFor(bytes);
            }
            return null;
        }
    }

    /** Instantaneous admitted bytes — volatile read, no lock. */
    public long current() {
        return currentBytes;
    }

    /** Instantaneous waiting bytes — volatile read, no lock. */
    public long waiting() {
        return waitingBytes;
    }

    /** Number of queued DEFER waiters. */
    public int waiterCount() {
        synchronized (mutex) {
            return waiters.size();
        }
    }

    /**
     * Releases {@code bytes} and admits queued DEFER waiters as freed space allows.
     * Equivalent to closing the {@link Releasable} returned by {@link #acquire}/{@link #tryAcquire}.
     * Callers holding a {@link Releasable} must close it instead — calling both double-releases.
     */
    public void release(long bytes) {
        final List<Exception> listenerFailures = new ArrayList<>();
        // Iterative (not recursive): each pass returns budget and admits newly-fitting waiters;
        // a grant whose executor rejects it has its bytes reclaimed on the next pass.
        // Grants are delivered off-mutex so a synchronously-failing listener cannot re-enter.
        long bytesToReturn = bytes;
        while (bytesToReturn > 0) {
            long reclaimed = 0;
            for (Waiter waiter : returnBudgetAndGrantWaiters(bytesToReturn)) {
                try {
                    waiter.executor().execute(() -> deliverGrant(waiter));
                } catch (Exception e) {
                    // executor rejected (node shutting down): reclaim budget, fail the waiter.
                    // bytesToReturn = reclaimed drives the next pass under the mutex.
                    reclaimed += waiter.bytes();
                    try {
                        waiter.listener().onFailure(e);
                    } catch (Exception listenerException) {
                        listenerFailures.add(listenerException);
                    }
                }
            }
            bytesToReturn = reclaimed;
        }
        ExceptionsHelper.maybeThrowRuntimeAndSuppress(listenerFailures);
    }

    // caller must hold mutex
    private boolean fits(long bytes) {
        // oversized request admitted when idle; budget may transiently exceed limitBytes
        return currentBytes + bytes <= limitBytes || currentBytes == 0;
    }

    /**
     * Ledger add + local counter update; returns {@code false} if the breaker trips (treat as
     * does-not-fit, no local state changed). Caller must hold mutex.
     * Oversized-when-idle grants use {@link CircuitBreaker#addWithoutBreaking} so the documented
     * transient over-limit does not falsely trip the parent hierarchy.
     */
    private boolean doGrant(long bytes) {
        final boolean oversized = currentBytes == 0 && bytes > limitBytes;
        if (oversized) {
            ledger.addWithoutBreaking(bytes);
        } else {
            try {
                ledger.addEstimateBytesAndMaybeBreak(bytes, name);
            } catch (CircuitBreakingException e) {
                return false;
            }
        }
        currentBytes += bytes;
        if (currentBytesHook != null) {
            currentBytesHook.accept(bytes);
        }
        return true;
    }

    private Releasable releasableFor(long bytes) {
        // releaseOnce: harmless double-release in prod; assertOnce surfaces it in tests
        return Releasables.assertOnce(Releasables.releaseOnce(() -> release(bytes)));
    }

    // returns budget and grants FIFO waiters that fit; caller delivers grants outside the mutex
    private List<Waiter> returnBudgetAndGrantWaiters(long bytes) {
        final List<Waiter> granted = new ArrayList<>();
        synchronized (mutex) {
            currentBytes -= bytes;
            ledger.addWithoutBreaking(-bytes);
            if (currentBytesHook != null) {
                currentBytesHook.accept(-bytes);
            }
            lastReleaseMillis = relativeTimeMillis.getAsLong();
            assert currentBytes >= 0 : "budget underflow [" + currentBytes + "] for [" + name + "]";
            Waiter head;
            // peek before poll: if doGrant fails (breaker trips), leave head in queue
            while ((head = waiters.peekFirst()) != null && fits(head.bytes())) {
                if (doGrant(head.bytes()) == false) {
                    break;
                }
                waiters.pollFirst();
                waitingBytes -= head.bytes();
                if (waitingBytesHook != null) {
                    waitingBytesHook.accept(-head.bytes());
                }
                granted.add(head);
            }
        }
        return granted;
    }

    // runs on the waiter's executor; closes the budget if the listener throws before taking ownership
    private void deliverGrant(Waiter waiter) {
        final Releasable budget = releasableFor(waiter.bytes());
        boolean handedOff = false;
        try {
            waiter.listener().onResponse(budget);
            handedOff = true;
        } finally {
            if (handedOff == false) {
                budget.close();
            }
        }
    }

    // caller must hold mutex; EsRejectedExecutionException on node shutdown → false (stall monitoring ends)
    private boolean tryScheduleStallCheckLocked(long delayMillis) {
        try {
            scheduler.schedule(this::checkForStalledWaiters, TimeValue.timeValueMillis(delayMillis), EsExecutors.DIRECT_EXECUTOR_SERVICE);
            return true;
        } catch (EsRejectedExecutionException e) {
            return false;
        }
    }

    /**
     * Fires after {@code stallThreshold} and re-arms while the queue stays non-empty.
     * Uses time-since-last-release — not waiter enqueue age — so that a large head that
     * cannot be admitted while smaller waiters trickle through is not mistaken for a stall.
     */
    private void checkForStalledWaiters() {
        final long noReleaseMillis;
        final long headBytes;
        final int waiterCount;
        final long waitingBytesSnapshot;
        final long currentBytesSnapshot;
        synchronized (mutex) {
            final Waiter head = waiters.peekFirst();
            if (head == null) {
                stallCheckScheduled = false;
                return;
            }
            noReleaseMillis = relativeTimeMillis.getAsLong() - lastReleaseMillis;
            headBytes = head.bytes();
            waiterCount = waiters.size();
            waitingBytesSnapshot = waitingBytes;
            currentBytesSnapshot = currentBytes;
        }
        final long nextDelayMillis;
        if (noReleaseMillis >= stallThresholdMillis) {
            stallListener.onStall(noReleaseMillis, headBytes, waiterCount, waitingBytesSnapshot, currentBytesSnapshot, limitBytes);
            nextDelayMillis = stallThresholdMillis;
        } else {
            nextDelayMillis = stallThresholdMillis - noReleaseMillis;
        }
        synchronized (mutex) {
            if (waiters.isEmpty()) {
                stallCheckScheduled = false;
            } else {
                stallCheckScheduled = tryScheduleStallCheckLocked(nextDelayMillis);
            }
        }
    }
}
