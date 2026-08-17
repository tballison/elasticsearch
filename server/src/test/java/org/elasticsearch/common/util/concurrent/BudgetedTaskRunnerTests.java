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
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;

public class BudgetedTaskRunnerTests extends ESTestCase {

    private static final NoopCircuitBreaker NOOP = new NoopCircuitBreaker("test");

    private static MemoryBudget budget(long limitBytes) {
        return new MemoryBudget("test", limitBytes, NOOP, () -> 0L, null, null, TimeValue.timeValueSeconds(60), null, null);
    }

    /** Minimal WeighedTask that counts runs and rejections. */
    private static final class CountingTask implements BudgetedTaskRunner.WeighedTask {
        private final long weight;
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger rejections = new AtomicInteger();
        final AtomicReference<Releasable> slot = new AtomicReference<>();

        CountingTask(long weight) {
            this.weight = weight;
        }

        @Override
        public long ramBytesUsed() {
            return weight;
        }

        @Override
        public void onResponse(Releasable s) {
            slot.set(s);
            runs.incrementAndGet();
        }

        @Override
        public void onFailure(Exception e) {
            rejections.incrementAndGet();
        }
    }

    // -------------------------------------------------------------------------
    // REJECT policy
    // -------------------------------------------------------------------------

    public void testRejectPolicyAdmitsWithinBudget() {
        var budget = budget(100);
        var runner = new BudgetedTaskRunner<CountingTask>(
            "test",
            4,
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            budget,
            BudgetedTaskRunner.OverloadPolicy.REJECT
        );

        var task = new CountingTask(50);
        runner.enqueueTask(task);
        assertThat(task.runs.get(), equalTo(1));
        assertThat(task.rejections.get(), equalTo(0));
        assertThat(budget.current(), equalTo(50L));

        task.slot.get().close();
        assertThat(budget.current(), equalTo(0L));
    }

    public void testRejectPolicyRejectsOverBudgetTask() {
        var budget = budget(100);
        var runner = new BudgetedTaskRunner<CountingTask>(
            "test",
            4,
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            budget,
            BudgetedTaskRunner.OverloadPolicy.REJECT
        );

        var task1 = new CountingTask(80);
        runner.enqueueTask(task1);
        assertThat(budget.current(), equalTo(80L));

        var task2 = new CountingTask(30); // exceeds budget → rejected
        runner.enqueueTask(task2);
        assertThat(task2.runs.get(), equalTo(0));
        assertThat(task2.rejections.get(), equalTo(1));
        assertThat(budget.current(), equalTo(80L));

        task1.slot.get().close();
        assertThat(budget.current(), equalTo(0L));
    }

    public void testRejectPolicyRejectCallsOnFailureNotOnResponse() {
        var budget = budget(10);
        var runner = new BudgetedTaskRunner<CountingTask>(
            "test",
            4,
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            budget,
            BudgetedTaskRunner.OverloadPolicy.REJECT
        );

        var admitted = new CountingTask(5);
        runner.enqueueTask(admitted);
        assertThat(admitted.runs.get(), equalTo(1));

        var rejected = new CountingTask(10); // exceeds remaining 5 bytes
        runner.enqueueTask(rejected);
        assertThat(rejected.rejections.get(), equalTo(1));
        assertThat(rejected.runs.get(), equalTo(0));
        assertThat(budget.current(), equalTo(5L));

        admitted.slot.get().close();
        assertThat(budget.current(), equalTo(0L));
    }

    public void testRejectionExceptionIsEsRejected() {
        var budget = budget(10);
        var runner = new BudgetedTaskRunner<>(
            "test",
            4,
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            budget,
            BudgetedTaskRunner.OverloadPolicy.REJECT
        );

        var admitted = new CountingTask(10);
        runner.enqueueTask(admitted);

        AtomicReference<Exception> failure = new AtomicReference<>();
        runner.enqueueTask(new BudgetedTaskRunner.WeighedTask() {
            @Override
            public long ramBytesUsed() {
                return 1;
            }

            @Override
            public void onResponse(Releasable r) {
                fail("should not be admitted");
            }

            @Override
            public void onFailure(Exception e) {
                failure.set(e);
            }
        });
        assertThat(failure.get(), instanceOf(EsRejectedExecutionException.class));

        admitted.slot.get().close();
    }

    /** Budget bytes released when the slot Releasable is closed. */
    public void testBytesReleasedOnSuccessfulRunWhenSlotClosed() {
        var budget = budget(100);
        var runner = new BudgetedTaskRunner<CountingTask>(
            "test",
            4,
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            budget,
            BudgetedTaskRunner.OverloadPolicy.REJECT
        );

        var task = new CountingTask(60);
        runner.enqueueTask(task);
        assertThat(budget.current(), equalTo(60L));

        task.slot.get().close();
        assertThat(budget.current(), equalTo(0L));
    }

    /** Budget bytes released via slot close even when the runnable was captured by a paused executor. */
    public void testBytesReleasedWhenExecutorRejectsAfterAdmission() {
        var budget = budget(100);
        List<Runnable> held = new ArrayList<>();
        var runner = new BudgetedTaskRunner<CountingTask>("test", 4, held::add, budget, BudgetedTaskRunner.OverloadPolicy.REJECT);

        var task = new CountingTask(60);
        runner.enqueueTask(task);
        assertThat(budget.current(), equalTo(60L));
        assertThat(held, hasSize(1));

        held.get(0).run();
        assertThat(task.runs.get(), equalTo(1));
        task.slot.get().close();
        assertThat(budget.current(), equalTo(0L));
    }

    // -------------------------------------------------------------------------
    // DEFER policy
    // -------------------------------------------------------------------------

    public void testDeferPolicyQueuesWhenOverBudget() {
        var budget = budget(100);
        var runner = new BudgetedTaskRunner<CountingTask>(
            "test",
            4,
            EsExecutors.DIRECT_EXECUTOR_SERVICE,
            budget,
            BudgetedTaskRunner.OverloadPolicy.DEFER
        );

        var task1 = new CountingTask(100);
        runner.enqueueTask(task1);
        assertThat(task1.runs.get(), equalTo(1));

        var task2 = new CountingTask(50);
        runner.enqueueTask(task2);
        assertThat(task2.runs.get(), equalTo(0));
        assertThat(budget.waiterCount(), equalTo(1));

        task1.slot.get().close(); // releases budget → task2 admitted
        assertThat(task2.runs.get(), equalTo(1));
        assertThat(budget.current(), equalTo(50L));
        task2.slot.get().close();
        assertThat(budget.current(), equalTo(0L));
    }

    // -------------------------------------------------------------------------
    // Concurrency cap
    // -------------------------------------------------------------------------

    public void testConcurrencyCapHonored() throws Exception {
        var budget = budget(1000);
        List<Runnable> captured = new ArrayList<>();

        // maxConcurrency = 2
        var runner = new BudgetedTaskRunner<CountingTask>("test", 2, captured::add, budget, BudgetedTaskRunner.OverloadPolicy.REJECT);

        var t1 = new CountingTask(10);
        var t2 = new CountingTask(10);
        var t3 = new CountingTask(10);
        runner.enqueueTask(t1);
        runner.enqueueTask(t2);
        runner.enqueueTask(t3);

        assertThat("at most 2 submitted to executor", captured.size(), equalTo(2));
        assertThat(runner.queueSize(), equalTo(1));

        captured.get(0).run();
        t1.slot.get().close(); // releases slot → t3 submitted
        assertThat(captured.size(), equalTo(3));

        captured.get(1).run();
        t2.slot.get().close();
        captured.get(2).run();
        t3.slot.get().close();

        assertThat(budget.current(), equalTo(0L));
    }

    // -------------------------------------------------------------------------
    // Red/green demonstration (INC-3482 cause 2)
    // -------------------------------------------------------------------------

    /**
     * RED: bare {@link ThrottledTaskRunner} with a paused drain queues all N tasks, retaining
     * N × weight bytes. This passes by asserting the bad behavior. Delete when no unguarded
     * runners remain in hot paths.
     */
    public void testUnguardedThrottledRunnerRetainsUnboundedBytesWhenDrainPaused() throws Exception {
        final long taskWeightBytes = 1024L * 1024L;
        final int taskCount = 50;

        List<Runnable> pausedQueue = new CopyOnWriteArrayList<>();
        final ThrottledTaskRunner throttledRunner = new ThrottledTaskRunner("unguarded", 1, pausedQueue::add);
        final AtomicInteger queuedCount = new AtomicInteger();

        CountDownLatch allSubmitted = new CountDownLatch(taskCount);
        for (int i = 0; i < taskCount; i++) {
            final long payload = taskWeightBytes;
            throttledRunner.enqueueTask(new ActionListener<>() {
                @Override
                public void onResponse(Releasable slot) {
                    try (slot) { /* work */ }
                }

                @Override
                public void onFailure(Exception e) {}

                @Override
                public String toString() {
                    return "task holding " + payload + " bytes";
                }
            });
            queuedCount.incrementAndGet();
            allSubmitted.countDown();
        }
        allSubmitted.await();

        // all N tasks queued regardless of memory — demonstrates unbounded retention
        // (INC-3482: 746K contexts × ~22 KB = ~16 GB)
        assertThat(
            "unguarded ThrottledTaskRunner queues all " + taskCount + " tasks regardless of memory",
            throttledRunner.queuedTasks() + (pausedQueue.isEmpty() ? 0 : 1),
            equalTo(taskCount)
        );

        pausedQueue.forEach(Runnable::run);
    }

    /**
     * GREEN: same scenario with {@link BudgetedTaskRunner}{@code (REJECT)}.
     * Admitted bytes plateau at the budget; excess tasks get {@code onFailure}.
     */
    public void testBudgetedRunnerPlateausAndRejectsExcessWhenDrainPaused() throws Exception {
        final long taskWeightBytes = 1024L * 1024L;
        final int taskCount = 50;
        final long budgetBytes = 10L * 1024L * 1024L; // 10 MB → admits 10 tasks

        final List<Runnable> pausedQueue = new CopyOnWriteArrayList<>();
        final MemoryBudget budget = budget(budgetBytes);
        final BudgetedTaskRunner<CountingTask> runner = new BudgetedTaskRunner<>(
            "guarded",
            Integer.MAX_VALUE,
            pausedQueue::add,
            budget,
            BudgetedTaskRunner.OverloadPolicy.REJECT
        );

        final List<CountingTask> admitted = new CopyOnWriteArrayList<>();
        final List<CountingTask> rejected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            var task = new CountingTask(taskWeightBytes);
            runner.enqueueTask(task);
            // paused executor hasn't run yet, so classify by onFailure (rejected), not runs
            if (task.rejections.get() > 0) {
                rejected.add(task);
            } else {
                admitted.add(task);
            }
        }

        long maxAdmitted = budgetBytes / taskWeightBytes;
        assertThat("admitted count bounded by budget", (long) admitted.size(), equalTo(maxAdmitted));
        assertThat("excess tasks rejected", rejected.size(), equalTo(taskCount - (int) maxAdmitted));
        assertThat("all rejected tasks get onFailure", rejected.stream().mapToInt(t -> t.rejections.get()).sum(), equalTo(rejected.size()));
        assertThat("admitted bytes at budget plateau", budget.current(), equalTo((long) admitted.size() * taskWeightBytes));

        pausedQueue.forEach(Runnable::run);
        admitted.forEach(t -> t.slot.get().close());
        assertThat(budget.current(), equalTo(0L));
    }
}
