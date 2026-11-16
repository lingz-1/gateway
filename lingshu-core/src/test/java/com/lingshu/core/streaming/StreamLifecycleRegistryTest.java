package com.lingshu.core.streaming;

import com.lingshu.core.observability.StreamTerminationReason;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamLifecycleRegistryTest {

    @Test
    void explicitlyCancelsOnlyTheOwningTenantAndIsIdempotent() {
        StreamLifecycleRegistry registry = new StreamLifecycleRegistry();
        StreamLifecycle lifecycle = registry.open("trace-1", "tenant-a");
        AtomicInteger cancellations = new AtomicInteger();
        lifecycle.attachUpstreamCancellation(cancellations::incrementAndGet);

        StreamLifecycleRegistry.CancellationResult forbidden =
                registry.requestCancellation("trace-1", "tenant-b");
        assertFalse(forbidden.found());
        assertEquals(0, cancellations.get());

        StreamLifecycleRegistry.CancellationResult first =
                registry.requestCancellation("trace-1", "tenant-a");
        StreamLifecycleRegistry.CancellationResult duplicate =
                registry.requestCancellation("trace-1", "tenant-a");

        assertTrue(first.found());
        assertTrue(first.accepted());
        assertEquals(StreamTerminationReason.CLIENT_CANCEL_REQUESTED, first.state());
        assertTrue(duplicate.found());
        assertFalse(duplicate.accepted());
        assertEquals(StreamTerminationReason.CLIENT_CANCEL_REQUESTED, duplicate.state());
        assertEquals(1, cancellations.get());
    }

    @Test
    void cancellationWinsRaceWithNormalCompletion() {
        StreamLifecycleRegistry registry = new StreamLifecycleRegistry();
        StreamLifecycle lifecycle = registry.open("trace-race", "tenant-a");

        registry.requestCancellation("trace-race", "tenant-a");
        StreamTerminationReason finalReason = registry.finish(
                lifecycle,
                StreamTerminationReason.COMPLETED
        );

        assertEquals(StreamTerminationReason.CLIENT_CANCEL_REQUESTED, finalReason);
    }

    @Test
    void rejectsDuplicateActiveTraceId() {
        StreamLifecycleRegistry registry = new StreamLifecycleRegistry();
        registry.open("trace-duplicate", "tenant-a");

        assertThrows(StreamLifecycleConflictException.class,
                () -> registry.open("trace-duplicate", "tenant-a"));
    }

    @Test
    void boundsRetainedTerminalStreams() {
        StreamLifecycleRegistry registry = new StreamLifecycleRegistry();

        for (int index = 0; index < 11_000; index++) {
            StreamLifecycle lifecycle = registry.open("trace-" + index, "tenant-a");
            registry.finish(lifecycle, StreamTerminationReason.COMPLETED);
        }

        assertTrue(registry.retainedCount() <= 10_000);
    }
}
