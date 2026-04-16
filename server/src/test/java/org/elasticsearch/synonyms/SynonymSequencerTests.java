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
import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class SynonymSequencerTests extends ESTestCase {

    public void testFirstTaskRunsImmediately() {
        SynonymSequencer sequencer = new SynonymSequencer();
        List<String> executed = new ArrayList<>();
        sequencer.submit(() -> executed.add("task1"));
        assertEquals(List.of("task1"), executed);
    }

    public void testSecondTaskQueuedUntilFirstCompletes() {
        SynonymSequencer sequencer = new SynonymSequencer();
        List<String> order = new ArrayList<>();
        AtomicReference<ActionListener<Void>> firstListener = new AtomicReference<>();

        sequencer.submit(() -> {
            order.add("task1-start");
            firstListener.set(sequencer.wrap(ActionListener.noop()));
        });
        assertEquals(List.of("task1-start"), order);

        sequencer.submit(() -> order.add("task2-start"));
        assertEquals("task2 must not run while task1 is in flight", List.of("task1-start"), order);

        firstListener.get().onResponse(null);
        assertEquals(List.of("task1-start", "task2-start"), order);
    }

    public void testMultipleTasksRunInSubmissionOrder() {
        SynonymSequencer sequencer = new SynonymSequencer();
        List<Integer> order = new ArrayList<>();
        List<ActionListener<Void>> listeners = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            final int taskId = i;
            sequencer.submit(() -> {
                order.add(taskId);
                listeners.add(sequencer.wrap(ActionListener.noop()));
            });
        }

        // Only task 0 should have run so far
        assertEquals(List.of(0), order);

        // Complete each task in order, verify the next one runs
        for (int i = 0; i < 4; i++) {
            listeners.get(i).onResponse(null);
            assertEquals(i + 2, order.size());
            assertEquals(i + 1, (int) order.get(i + 1));
        }
        listeners.get(4).onResponse(null);
    }

    public void testOnFailureAlsoDrainsQueue() {
        SynonymSequencer sequencer = new SynonymSequencer();
        List<String> order = new ArrayList<>();
        AtomicReference<ActionListener<Void>> firstListener = new AtomicReference<>();

        sequencer.submit(() -> {
            order.add("task1-start");
            firstListener.set(sequencer.wrap(ActionListener.noop()));
        });

        sequencer.submit(() -> order.add("task2-start"));
        assertEquals(List.of("task1-start"), order);

        // Fail task1 — task2 must still run
        firstListener.get().onFailure(new RuntimeException("simulated failure"));
        assertEquals("onFailure must drain the queue", List.of("task1-start", "task2-start"), order);
    }

    public void testQueueAcceptsNewTaskAfterDrained() {
        SynonymSequencer sequencer = new SynonymSequencer();
        List<String> order = new ArrayList<>();
        AtomicReference<ActionListener<Void>> listener = new AtomicReference<>();

        sequencer.submit(() -> {
            order.add("task1");
            listener.set(sequencer.wrap(ActionListener.noop()));
        });
        listener.get().onResponse(null);

        // Queue is now empty and idle — new task should run immediately
        sequencer.submit(() -> order.add("task2"));
        assertEquals(List.of("task1", "task2"), order);
    }
}
