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
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;

import java.util.concurrent.Executor;

/**
 * Composes {@link MemoryBudget} with the drain pattern of {@link AbstractThrottledTaskRunner}:
 * a task is admitted only when both the memory budget allows it and a concurrency slot is free.
 *
 * @param <T> task type; must provide its memory weight via {@link WeighedTask#ramBytesUsed()}
 */
public class BudgetedTaskRunner<T extends BudgetedTaskRunner.WeighedTask> {

    /**
     * A task that knows its memory weight. {@link #onResponse} receives the combined
     * concurrency+budget slot (close to release both); {@link #onFailure} is called on
     * REJECT or executor failure.
     */
    public interface WeighedTask extends ActionListener<Releasable> {
        /** Bytes this task holds from admission until the returned {@link Releasable} is closed. Must be {@code > 0}. */
        long ramBytesUsed();
    }

    /** Overload response when budget is exceeded. */
    public enum OverloadPolicy {
        /** Reject over-budget tasks immediately via {@link WeighedTask#onFailure}. */
        REJECT,
        /** Queue over-budget tasks inside {@link MemoryBudget} (FIFO); admit when budget frees. */
        DEFER
    }

    private final MemoryBudget budget;
    private final OverloadPolicy policy;
    private final ThrottledTaskRunner throttledRunner;

    // Pre-built shared rejection: EsRejectedExecutionException.fillInStackTrace() is a no-op,
    // and sharing avoids even the per-rejection allocation under a flood.
    private final EsRejectedExecutionException sharedRejection;

    public BudgetedTaskRunner(String name, int maxConcurrency, Executor delegate, MemoryBudget budget, OverloadPolicy policy) {
        this.budget = budget;
        this.policy = policy;
        this.throttledRunner = new ThrottledTaskRunner(name, maxConcurrency, delegate);
        this.sharedRejection = new EsRejectedExecutionException(
            "memory budget exceeded for [" + name + "]; task rejected (policy=" + policy + ")",
            false
        );
    }

    /**
     * Admits {@code task} per the configured policy.
     * Budget bytes are released exactly once: when the {@link Releasable} passed to
     * {@link WeighedTask#onResponse} is closed, or inside {@link WeighedTask#onFailure}
     * (for executor failures after admission).
     */
    public void enqueueTask(T task) {
        final long bytes = task.ramBytesUsed();
        switch (policy) {
            case REJECT -> {
                final Releasable grant = budget.tryAcquire(bytes);
                if (grant == null) {
                    task.onFailure(sharedRejection);
                    return;
                }
                throttledRunner.enqueueTask(wrapWithBudget(task, grant));
            }
            case DEFER -> budget.acquire(
                bytes,
                Runnable::run,
                ActionListener.wrap(grant -> throttledRunner.enqueueTask(wrapWithBudget(task, grant)), task::onFailure)
            );
        }
    }

    private ActionListener<Releasable> wrapWithBudget(T task, Releasable grant) {
        return new ActionListener<>() {
            @Override
            public void onResponse(Releasable slot) {
                task.onResponse(Releasables.wrap(slot, grant));
            }

            @Override
            public void onFailure(Exception e) {
                try (grant) {
                    task.onFailure(e);
                }
            }
        };
    }

    /** Tasks queued waiting for a concurrency slot (excludes budget waiters). */
    public int queueSize() {
        return throttledRunner.queuedTasks();
    }

    /** The underlying memory budget. */
    public MemoryBudget budget() {
        return budget;
    }
}
