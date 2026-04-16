/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.synonyms;

import org.elasticsearch.action.ActionListener;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Serializes synonym write operations on the master node. Because synonym mutations are routed to
 * the elected master via {@code TransportMasterNodeAction}, a single instance of this class is
 * sufficient to prevent concurrent synonym writes across the entire cluster.
 *
 * <p>Callers wrap their {@link ActionListener} with {@link #wrap} and pass the result to the
 * underlying service call. The sequencer starts the next queued operation only after the wrapped
 * listener is notified (success or failure), ensuring at-most-one in-flight write at a time.
 *
 * <p><b>Liveness warning:</b> if a synonym operation's async chain fails to notify its listener
 * (e.g. due to an unhandled exception that swallows the callback), {@code onComplete} is never
 * called, {@code busy} remains {@code true}, and all subsequent synonym writes queue up
 * indefinitely until the node is restarted. Callers must ensure that every code path through
 * {@link SynonymsManagementAPIService} eventually calls either {@code onResponse} or
 * {@code onFailure} on the listener produced by {@link #wrap}.
 */
public class SynonymSequencer {

    private final Deque<Runnable> pending = new ArrayDeque<>();
    private boolean busy = false;

    /**
     * Submits a synonym write operation for sequential execution. If no operation is currently
     * running, {@code task} is started immediately; otherwise it is queued until the current
     * operation completes.
     *
     * @param task a {@link Runnable} that starts the async synonym write and calls
     *             {@link #onComplete} when it finishes (via a listener produced by {@link #wrap})
     */
    public void submit(Runnable task) {
        Runnable toRun = null;
        synchronized (this) {
            pending.addLast(task);
            if (busy == false) {
                busy = true;
                toRun = pending.pollFirst();
            }
        }
        if (toRun != null) {
            toRun.run();
        }
    }

    /**
     * Wraps a listener so that {@link #onComplete} is called automatically when the operation
     * finishes, regardless of success or failure.
     */
    public <T> ActionListener<T> wrap(ActionListener<T> listener) {
        return ActionListener.runAfter(listener, this::onComplete);
    }

    /**
     * Called when the current operation finishes. Starts the next queued operation, if any.
     * This is called automatically when using {@link #wrap}.
     */
    private void onComplete() {
        Runnable toRun;
        synchronized (this) {
            toRun = pending.pollFirst();
            if (toRun == null) {
                busy = false;
            }
        }
        if (toRun != null) {
            toRun.run();
        }
    }
}
