/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.common.util.concurrent;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class MemoryBudgetTests extends ESTestCase {

    /** Completes deferred grants on the releasing thread for deterministic FIFO drain. */
    private static final Executor INLINE_GRANTS = Runnable::run;

    private static final NoopCircuitBreaker NOOP = new NoopCircuitBreaker("test");

    private static MemoryBudget budgetWithLimit(long limitBytes) {
        return new MemoryBudget("test", limitBytes, NOOP, () -> 0L, null, null, TimeValue.timeValueSeconds(60), null, null);
    }

    private static ActionListener<Releasable> collectTo(List<Releasable> granted) {
        return ActionListener.wrap(granted::add, e -> fail(e, "unexpected failure"));
    }

    /** Stub ScheduledCancellable for fake schedulers used in stall-detection tests. */
    private static Scheduler.ScheduledCancellable neverCancelled() {
        return new Scheduler.ScheduledCancellable() {
            @Override
            public boolean cancel() {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public long getDelay(TimeUnit unit) {
                return 0L;
            }

            @Override
            public int compareTo(java.util.concurrent.Delayed o) {
                return 0;
            }
        };
    }

    // -------------------------------------------------------------------------
    // DEFER (acquire) semantics
    // -------------------------------------------------------------------------

    public void testGrantsImmediatelyWithinLimit() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(60, INLINE_GRANTS, collectTo(granted));
        budget.acquire(40, INLINE_GRANTS, collectTo(granted));
        assertThat(granted, hasSize(2));
        assertThat(budget.current(), equalTo(100L));
        assertThat(budget.waiterCount(), equalTo(0));
        granted.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    public void testQueuesWhenOverLimitAndDrainsFifoOnRelease() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(80, INLINE_GRANTS, collectTo(granted));

        List<String> grantOrder = new CopyOnWriteArrayList<>();
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(50, INLINE_GRANTS, ActionListener.wrap(r -> {
            grantOrder.add("first");
            queuedGrants.add(r);
        }, e -> fail(e, "unexpected failure")));
        budget.acquire(30, INLINE_GRANTS, ActionListener.wrap(r -> {
            grantOrder.add("second");
            queuedGrants.add(r);
        }, e -> fail(e, "unexpected failure")));
        assertThat(grantOrder, empty());
        assertThat(budget.waiterCount(), equalTo(2));

        // the 30-byte waiter would fit alongside the 80 in flight, but must not jump the 50-byte head
        granted.get(0).close();
        assertThat(grantOrder, contains("first", "second"));
        assertThat(budget.waiterCount(), equalTo(0));
        assertThat(budget.current(), equalTo(80L));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    public void testLaterAcquirersQueueBehindExistingWaiters() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(90, INLINE_GRANTS, collectTo(granted));
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(50, INLINE_GRANTS, collectTo(queuedGrants));
        // 5 bytes fit now but granting them would starve the 50-byte head
        budget.acquire(5, INLINE_GRANTS, collectTo(queuedGrants));
        assertThat(queuedGrants, empty());
        assertThat(budget.waiterCount(), equalTo(2));
        granted.get(0).close();
        assertThat(queuedGrants, hasSize(2));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    public void testOversizedRequestGrantedWhenNothingInFlight() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(500, INLINE_GRANTS, collectTo(granted));
        assertThat(granted, hasSize(1));
        assertThat(budget.current(), equalTo(500L));

        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(10, INLINE_GRANTS, collectTo(queuedGrants));
        assertThat(queuedGrants, empty());
        granted.get(0).close();
        assertThat(queuedGrants, hasSize(1));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    public void testOversizedWaiterGrantedOnceInFlightDrains() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(60, INLINE_GRANTS, collectTo(granted));
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(500, INLINE_GRANTS, collectTo(queuedGrants));
        assertThat(queuedGrants, empty());
        granted.get(0).close();
        assertThat(queuedGrants, hasSize(1));
        assertThat(budget.current(), equalTo(500L));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    public void testDeferredGrantCompletesOnSuppliedExecutor() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(80, INLINE_GRANTS, collectTo(granted));

        List<Runnable> deferredGrants = new ArrayList<>();
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(50, deferredGrants::add, collectTo(queuedGrants));
        assertThat(budget.waiterCount(), equalTo(1));

        granted.get(0).close();
        assertThat(deferredGrants, hasSize(1));
        assertThat(queuedGrants, empty());
        assertThat(budget.current(), equalTo(50L));
        deferredGrants.get(0).run();
        assertThat(queuedGrants, hasSize(1));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    public void testRejectedGrantReturnsBudgetAndFailsWaiter() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(100, INLINE_GRANTS, collectTo(granted));

        AtomicReference<Exception> failure = new AtomicReference<>();
        budget.acquire(60, r -> { throw new EsRejectedExecutionException("simulated rejection", true); }, ActionListener.wrap(r -> {
            fail("must not be granted, the executor rejected the grant");
        }, failure::set));
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(40, INLINE_GRANTS, collectTo(queuedGrants));
        assertThat(budget.waiterCount(), equalTo(2));

        granted.get(0).close();
        assertThat(failure.get(), instanceOf(EsRejectedExecutionException.class));
        assertThat(queuedGrants, hasSize(1));
        assertThat(budget.current(), equalTo(40L));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
        assertThat(budget.waiterCount(), equalTo(0));
    }

    public void testReclaimedBytesFromRejectedGrantFundWaiterThatDidNotFitInitially() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(100, INLINE_GRANTS, collectTo(granted));

        AtomicReference<Exception> failure = new AtomicReference<>();
        budget.acquire(80, r -> { throw new EsRejectedExecutionException("simulated rejection", true); }, ActionListener.wrap(r -> {
            fail("must not be granted, the executor rejected the grant");
        }, failure::set));
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(80, INLINE_GRANTS, collectTo(queuedGrants));
        assertThat(budget.waiterCount(), equalTo(2));

        granted.get(0).close();
        assertThat(failure.get(), instanceOf(EsRejectedExecutionException.class));
        assertThat(queuedGrants, hasSize(1));
        assertThat(budget.current(), equalTo(80L));
        assertThat(budget.waiterCount(), equalTo(0));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    public void testThrowingOnFailureDoesNotStrandSubsequentWaiters() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(100, INLINE_GRANTS, collectTo(granted));

        budget.acquire(30, r -> { throw new EsRejectedExecutionException("simulated rejection", true); }, new ActionListener<>() {
            @Override
            public void onResponse(Releasable r) {
                fail("must not be granted, the executor rejected the grant");
            }

            @Override
            public void onFailure(Exception e) {
                throw new RuntimeException("simulated onFailure failure");
            }
        });
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(40, INLINE_GRANTS, collectTo(queuedGrants));
        assertThat(budget.waiterCount(), equalTo(2));

        var thrown = expectThrows(RuntimeException.class, () -> granted.get(0).close());
        assertThat(thrown.getMessage(), containsString("simulated onFailure failure"));

        assertThat(queuedGrants, hasSize(1));
        assertThat(budget.current(), equalTo(40L));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
        assertThat(budget.waiterCount(), equalTo(0));
    }

    // -------------------------------------------------------------------------
    // REJECT (tryAcquire) semantics
    // -------------------------------------------------------------------------

    public void testTryAcquireSucceedsWhenFits() {
        var budget = budgetWithLimit(100);
        Releasable r = budget.tryAcquire(60);
        assertThat(r, notNullValue());
        assertThat(budget.current(), equalTo(60L));
        r.close();
        assertThat(budget.current(), equalTo(0L));
    }

    public void testTryAcquireFailsWhenOverBudget() {
        var budget = budgetWithLimit(100);
        Releasable r1 = budget.tryAcquire(80);
        assertThat(r1, notNullValue());
        assertThat(budget.tryAcquire(30), nullValue());
        r1.close();
        Releasable r2 = budget.tryAcquire(30);
        assertThat(r2, notNullValue());
        r2.close();
    }

    public void testTryAcquireFailsWhenDeferWaiterQueued() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(90, INLINE_GRANTS, collectTo(granted));
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(20, INLINE_GRANTS, collectTo(queuedGrants));
        assertThat(budget.waiterCount(), equalTo(1));

        // 5 bytes would fit but must not overtake the queued DEFER waiter
        assertThat(budget.tryAcquire(5), nullValue());

        granted.forEach(Releasable::close);
        assertThat(queuedGrants, hasSize(1));
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    // -------------------------------------------------------------------------
    // High-water marks
    // -------------------------------------------------------------------------

    /** Peaks record the transient max between reads; a sampled gauge would miss it after release. */
    public void testPeaksRecordTransientsAndResetToInstantaneous() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        List<Releasable> queuedGrants = new CopyOnWriteArrayList<>();
        budget.acquire(80, INLINE_GRANTS, collectTo(granted));
        budget.acquire(50, INLINE_GRANTS, collectTo(queuedGrants)); // queues: waiting=50
        budget.acquire(30, INLINE_GRANTS, collectTo(queuedGrants)); // queues: waiting=80
        granted.forEach(Releasable::close); // drains both waiters inline
        queuedGrants.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
        assertThat(budget.waiting(), equalTo(0L));

        assertThat(budget.peakCurrentAndReset(), equalTo(80L));
        assertThat(budget.peakWaitingAndReset(), equalTo(80L));
        // window reset to instantaneous (0) — quiet period reports no phantom peak
        assertThat(budget.peakCurrentAndReset(), equalTo(0L));
        assertThat(budget.peakWaitingAndReset(), equalTo(0L));
    }

    /** Reset lands on the instantaneous value, not zero: bytes still held must stay visible as the floor. */
    public void testPeakResetKeepsHeldBytesAsFloor() {
        var budget = budgetWithLimit(100);
        List<Releasable> granted = new ArrayList<>();
        budget.acquire(70, INLINE_GRANTS, collectTo(granted));
        Releasable transientGrant = budget.tryAcquire(20);
        assertThat(transientGrant, notNullValue());
        transientGrant.close();

        assertThat(budget.peakCurrentAndReset(), equalTo(90L));
        // 70 still held: next window starts at 70, not 0
        assertThat(budget.peakCurrentAndReset(), equalTo(70L));
        granted.forEach(Releasable::close);
    }

    // -------------------------------------------------------------------------
    // Stall detection with virtual clock
    // -------------------------------------------------------------------------

    public void testStallListenerFiresAfterThreshold() {
        final AtomicLong clock = new AtomicLong(0L);
        final List<Runnable> scheduled = new ArrayList<>();
        final List<long[]> stalls = new ArrayList<>();

        var budget = new MemoryBudget("stall-test", 100, NOOP, clock::get, (command, delay, executor) -> {
            scheduled.add(command);
            return neverCancelled();
        },
            (noReleaseMillis, headBytes, waiterCount, waitingBytes, currentBytes, limitBytes) -> stalls.add(
                new long[] { noReleaseMillis, headBytes, waiterCount, waitingBytes, currentBytes }
            ),
            TimeValue.timeValueMillis(50),
            null,
            null
        );

        Releasable grant = budget.tryAcquire(100);
        assertThat(grant, notNullValue());

        List<Releasable> queued = new ArrayList<>();
        budget.acquire(10, INLINE_GRANTS, collectTo(queued));
        assertThat(scheduled, hasSize(1));
        assertThat(stalls, empty());

        clock.set(50L);
        scheduled.get(0).run();
        assertThat(stalls, hasSize(1));
        assertThat(stalls.get(0)[0], equalTo(50L));
        assertThat(scheduled, hasSize(2)); // re-armed
    }

    public void testStallListenerDoesNotFireIfReleasedRecently() {
        final AtomicLong clock = new AtomicLong(0L);
        final List<Runnable> scheduled = new ArrayList<>();
        final List<long[]> stalls = new ArrayList<>();

        var budget = new MemoryBudget("stall-test", 100, NOOP, clock::get, (command, delay, executor) -> {
            scheduled.add(command);
            return neverCancelled();
        },
            (noReleaseMillis, headBytes, waiterCount, waitingBytes, currentBytes, limitBytes) -> stalls.add(new long[] { noReleaseMillis }),
            TimeValue.timeValueMillis(50),
            null,
            null
        );

        Releasable grant1 = budget.tryAcquire(100);
        List<Releasable> queued = new ArrayList<>();
        budget.acquire(10, INLINE_GRANTS, collectTo(queued));
        assertThat(scheduled, hasSize(1));

        // release at 30ms — resets lastReleaseMillis
        clock.set(30L);
        grant1.close();
        assertThat(queued, hasSize(1));
        queued.get(0).close();

        Releasable grant2 = budget.tryAcquire(100);
        List<Releasable> queued2 = new ArrayList<>();
        budget.acquire(10, INLINE_GRANTS, collectTo(queued2));

        // fire at 60ms: timeSinceLastRelease = 30ms < 50ms → no stall
        clock.set(60L);
        scheduled.get(scheduled.size() - 1).run();
        assertThat(stalls, empty());

        grant2.close();
        assertThat(queued2, hasSize(1));
        queued2.forEach(Releasable::close);
    }

    public void testStallCheckDisarmsWhenQueueDrains() {
        final AtomicLong clock = new AtomicLong(0L);
        final List<Runnable> scheduled = new ArrayList<>();

        var budget = new MemoryBudget("stall-test", 100, NOOP, clock::get, (command, delay, executor) -> {
            scheduled.add(command);
            return neverCancelled();
        },
            (noReleaseMillis, headBytes, waiterCount, waitingBytes, currentBytes, limitBytes) -> {},
            TimeValue.timeValueMillis(50),
            null,
            null
        );

        Releasable grant = budget.tryAcquire(100);
        List<Releasable> queued = new ArrayList<>();
        budget.acquire(10, INLINE_GRANTS, collectTo(queued));
        assertThat(scheduled, hasSize(1));

        clock.set(10L);
        grant.close();
        assertThat(queued, hasSize(1));
        queued.forEach(Releasable::close);

        // queue drained before check fires → no re-arm
        clock.set(50L);
        int scheduledBeforeCheck = scheduled.size();
        scheduled.get(0).run();
        assertThat(scheduled.size(), equalTo(scheduledBeforeCheck));
    }

    // -------------------------------------------------------------------------
    // CircuitBreaker trip semantics
    // -------------------------------------------------------------------------

    /**
     * Trips {@link CircuitBreaker#addEstimateBytesAndMaybeBreak} on demand.
     * {@link CircuitBreaker#addWithoutBreaking} never trips (used for releases and oversized grants).
     */
    private static final class TrippableBreaker implements CircuitBreaker {
        volatile boolean shouldTrip = false;
        private final AtomicLong used = new AtomicLong(0);

        @Override
        public void circuitBreak(String fieldName, long bytesNeeded) {}

        @Override
        public void addEstimateBytesAndMaybeBreak(long bytes, String label) throws CircuitBreakingException {
            if (shouldTrip) {
                throw new CircuitBreakingException("test trip", 0L, 0L, Durability.TRANSIENT);
            }
            used.addAndGet(bytes);
        }

        @Override
        public void addWithoutBreaking(long bytes) {
            used.addAndGet(bytes);
        }

        @Override
        public long getUsed() {
            return used.get();
        }

        @Override
        public long getLimit() {
            return 0;
        }

        @Override
        public double getOverhead() {
            return 1.0;
        }

        @Override
        public long getTrippedCount() {
            return 0;
        }

        @Override
        public String getName() {
            return "trippable-test";
        }

        @Override
        public Durability getDurability() {
            return Durability.TRANSIENT;
        }

        @Override
        public void setLimitAndOverhead(long limit, double overhead) {}
    }

    /** Breaker trip → acquire queues (DEFER treats trip as does-not-fit). */
    public void testBreakerTripCausesDeferAcquireToQueue() {
        var breaker = new TrippableBreaker();
        var budget = new MemoryBudget("trip-test", 1000, breaker, () -> 0L, null, null, TimeValue.timeValueSeconds(60), null, null);

        Releasable r = budget.tryAcquire(50);
        assertThat(r, notNullValue());

        breaker.shouldTrip = true;
        List<Releasable> queued = new ArrayList<>();
        budget.acquire(10, INLINE_GRANTS, collectTo(queued));
        assertThat(queued, empty());
        assertThat(budget.waiterCount(), equalTo(1));

        breaker.shouldTrip = false;
        r.close();
        assertThat(queued, hasSize(1));
        queued.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
        assertThat(budget.waiterCount(), equalTo(0));
    }

    /** Breaker trip → tryAcquire returns null (REJECT treats trip as does-not-fit). */
    public void testBreakerTripCausesRejectTryAcquireToReturnNull() {
        var breaker = new TrippableBreaker();
        var budget = new MemoryBudget("trip-test", 1000, breaker, () -> 0L, null, null, TimeValue.timeValueSeconds(60), null, null);

        breaker.shouldTrip = true;
        assertThat(budget.tryAcquire(10), nullValue());
        assertThat(budget.current(), equalTo(0L));

        breaker.shouldTrip = false;
        Releasable r = budget.tryAcquire(10);
        assertThat(r, notNullValue());
        r.close();
        assertThat(budget.current(), equalTo(0L));
    }

    /** Waiter queued during a trip is granted once the breaker recovers and budget is released. */
    public void testWaiterQueuedOnTripIsGrantedAfterReleaseAndBreakerRecovers() {
        var breaker = new TrippableBreaker();
        var budget = new MemoryBudget("trip-test", 1000, breaker, () -> 0L, null, null, TimeValue.timeValueSeconds(60), null, null);

        Releasable r = budget.tryAcquire(200);
        assertThat(r, notNullValue());

        breaker.shouldTrip = true;
        List<Releasable> queued = new ArrayList<>();
        budget.acquire(100, INLINE_GRANTS, collectTo(queued));
        assertThat(queued, empty());
        assertThat(budget.waiterCount(), equalTo(1));

        breaker.shouldTrip = false;
        r.close();
        assertThat(queued, hasSize(1));
        assertThat(budget.waiterCount(), equalTo(0));
        queued.forEach(Releasable::close);
        assertThat(budget.current(), equalTo(0L));
    }

    // -------------------------------------------------------------------------
    // Randomized and concurrent
    // -------------------------------------------------------------------------

    public void testRandomizedAcquireReleaseNeverExceedsLimitAndFullyDrains() {
        final long limit = randomLongBetween(100, 1000);
        var budget = budgetWithLimit(limit);
        List<Releasable> outstanding = new CopyOnWriteArrayList<>();
        int acquires = randomIntBetween(50, 200);
        for (int i = 0; i < acquires; i++) {
            long bytes = randomLongBetween(1, limit / 2);
            budget.acquire(bytes, INLINE_GRANTS, ActionListener.wrap(outstanding::add, e -> fail(e, "unexpected failure")));
            assertThat("in-flight bytes exceed limit", budget.current(), lessThanOrEqualTo(limit));
            if (outstanding.isEmpty() == false && randomBoolean()) {
                outstanding.remove(randomIntBetween(0, outstanding.size() - 1)).close();
                assertThat("in-flight bytes exceed limit", budget.current(), lessThanOrEqualTo(limit));
            }
        }
        while (outstanding.isEmpty() == false) {
            outstanding.remove(0).close();
        }
        assertThat(budget.waiterCount(), equalTo(0));
        assertThat(budget.current(), equalTo(0L));
    }

    public void testConcurrentAcquireReleaseNeverExceedsLimitAndFullyDrains() {
        final long limit = randomLongBetween(100, 1000);
        var budget = budgetWithLimit(limit);
        final int threads = between(4, 8);
        final int opsPerThread = between(200, 500);
        final int totalAcquires = threads * opsPerThread;

        final long[][] acquireSizes = new long[threads][opsPerThread];
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < opsPerThread; i++) {
                acquireSizes[t][i] = randomLongBetween(1, limit / 2);
            }
        }

        final Queue<Releasable> outstanding = new ConcurrentLinkedQueue<>();
        final AtomicInteger grantCount = new AtomicInteger();
        startInParallel(threads, t -> {
            for (int i = 0; i < opsPerThread; i++) {
                budget.acquire(acquireSizes[t][i], INLINE_GRANTS, ActionListener.wrap(r -> {
                    grantCount.incrementAndGet();
                    outstanding.add(r);
                }, e -> fail(e, "unexpected failure")));
                assertThat("in-flight bytes exceed limit", budget.current(), lessThanOrEqualTo(limit));
                Releasable release = outstanding.poll();
                if (release != null) {
                    release.close();
                }
            }
        });

        Releasable release;
        while ((release = outstanding.poll()) != null) {
            release.close();
            assertThat("in-flight bytes exceed limit", budget.current(), lessThanOrEqualTo(limit));
        }
        assertThat(grantCount.get(), equalTo(totalAcquires));
        assertThat(budget.current(), equalTo(0L));
        assertThat(budget.waiterCount(), equalTo(0));
    }
}
