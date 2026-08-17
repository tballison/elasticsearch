/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.action.search;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.BudgetedTaskRunner;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.MemoryBudget;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.AbstractTransportRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

public class SearchTransportServiceTests extends ESTestCase {

    /**
     * Verifies that the free-context executor concurrency is half the allocated processors, with a minimum of 1.
     * A node with many processors must be able to drain free-context requests concurrently; with a single drainer
     * the queue can grow unboundedly under burst traffic and exhaust the heap.
     */
    public void testFreeContextConcurrency() {
        // single processor → minimum of 1
        assertThat(SearchTransportService.freeContextConcurrency(settings(1)), equalTo(1));
        // two processors → 1
        assertThat(SearchTransportService.freeContextConcurrency(settings(2)), equalTo(1));
        // four processors → 2
        assertThat(SearchTransportService.freeContextConcurrency(settings(4)), equalTo(2));
        // eight processors → 4
        assertThat(SearchTransportService.freeContextConcurrency(settings(8)), equalTo(4));
        // default settings use Runtime.availableProcessors() → at least 1
        assertThat(SearchTransportService.freeContextConcurrency(Settings.EMPTY), greaterThanOrEqualTo(1));
    }

    private static Settings settings(int processors) {
        return Settings.builder().put(EsExecutors.NODE_PROCESSORS_SETTING.getKey(), processors).build();
    }

    /**
     * Verifies that {@link SearchTransportService#countingRequest} and
     * {@link SearchTransportService#countingReader} report the same byte count for an identical payload,
     * ensuring that the send-side and receive-side accounting are consistent.
     */
    public void testRequestAndReaderBytesAgree() throws IOException {
        int intVal = randomInt();
        String strVal = randomAlphaOfLengthBetween(1, 64);
        long longVal = randomLong();
        boolean boolVal = randomBoolean();
        String optStr = randomBoolean() ? null : randomAlphaOfLength(10);

        AbstractTransportRequest inner = new AbstractTransportRequest() {
            @Override
            public void writeTo(StreamOutput out) throws IOException {
                out.writeInt(intVal);
                out.writeString(strVal);
                out.writeLong(longVal);
                out.writeBoolean(boolVal);
                out.writeOptionalString(optStr);
            }
        };

        BytesStreamOutput out = new BytesStreamOutput();
        out.setTransportVersion(TransportVersion.current());

        long[] written = new long[1];
        SearchTransportService.countingRequest(inner, b -> written[0] = b).writeTo(out);

        long[] read = new long[1];
        Writeable.Reader<String> countingReader = SearchTransportService.countingReader(in -> {
            in.readInt();
            in.readString();
            in.readLong();
            in.readBoolean();
            in.readOptionalString();
            return "ok";
        }, b -> read[0] = b);

        var streamInput = out.bytes().streamInput();
        streamInput.setTransportVersion(TransportVersion.current());
        countingReader.read(streamInput);

        assertThat(written[0], equalTo(read[0]));
        assertThat(written[0], greaterThan(0L));
    }

    /**
     * Verifies that {@link SearchTransportService#countingRequest} counts the exact number of bytes
     * written by the inner request's {@code writeTo}.
     */
    public void testCountingRequestCountsBytes() throws IOException {
        AbstractTransportRequest request = new AbstractTransportRequest() {
            @Override
            public void writeTo(StreamOutput out) throws IOException {
                out.writeInt(1);
                out.writeInt(2);
            }
        };

        BytesStreamOutput out = new BytesStreamOutput();
        out.setTransportVersion(TransportVersion.current());

        long[] counted = new long[1];
        SearchTransportService.countingRequest(request, b -> counted[0] = b).writeTo(out);

        // Two ints = 8 bytes
        assertThat(counted[0], equalTo(8L));
        assertThat(out.size(), equalTo(8));
    }

    /**
     * Under a flood, the free_context {@link BudgetedTaskRunner} admits at most
     * {@code budget / taskWeight} tasks (REJECT policy) and calls {@code onFailure} for the rest.
     * Uses a paused executor to hold budget open during the flood — inline execution would
     * cycle the budget back to zero between submissions, masking the plateau.
     */
    public void testFreeContextBudgetBoundsQueueUnderFlood() {
        final long taskBytes = SearchTransportService.FREE_CONTEXT_TASK_BYTES;
        final long budgetBytes = taskBytes * 5;
        final int taskCount = 20;
        final int maxAdmitted = (int) (budgetBytes / taskBytes);

        final List<Runnable> pausedQueue = new CopyOnWriteArrayList<>();

        var budget = new MemoryBudget(
            "free_context_test",
            budgetBytes,
            new NoopCircuitBreaker("free_context_test"),
            () -> 0L,
            null,
            null,
            TimeValue.timeValueSeconds(60),
            null,
            null
        );
        var runner = new BudgetedTaskRunner<BudgetedTaskRunner.WeighedTask>(
            "free_context_test",
            Integer.MAX_VALUE,
            pausedQueue::add,
            budget,
            BudgetedTaskRunner.OverloadPolicy.REJECT
        );

        final AtomicInteger rejected = new AtomicInteger();
        final List<Releasable> openSlots = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            runner.enqueueTask(new BudgetedTaskRunner.WeighedTask() {
                @Override
                public long ramBytesUsed() {
                    return taskBytes;
                }

                @Override
                public void onResponse(Releasable slot) {
                    openSlots.add(slot);
                }

                @Override
                public void onFailure(Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }

        assertThat("excess tasks rejected", rejected.get(), equalTo(taskCount - maxAdmitted));
        assertThat("budget at plateau", budget.current(), equalTo((long) maxAdmitted * taskBytes));

        pausedQueue.forEach(Runnable::run);
        assertThat("all admitted tasks ran", openSlots.size(), equalTo(maxAdmitted));
        openSlots.forEach(Releasable::close);
        assertThat("budget fully drained after release", budget.current(), equalTo(0L));
    }
}
