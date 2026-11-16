package com.lingshu.core.streaming;

import com.lingshu.core.observability.StreamTerminationReason;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamCancellationCoordinatorTest {

    @Test
    void routesCancellationToTheOwningCoreInstance() {
        Map<String, StreamOwnerDirectory.StreamOwner> owners = new ConcurrentHashMap<>();
        Map<String, StreamCancellationCoordinator> instances = new ConcurrentHashMap<>();
        StreamCancellationForwarder forwarder = (owner, traceId, tenantId) ->
                instances.get(owner.instanceId()).cancelLocal(traceId, tenantId);
        StreamCancellationCoordinator coreA = coordinator("core-a", 8081, owners, forwarder);
        StreamCancellationCoordinator coreB = coordinator("core-b", 8082, owners, forwarder);
        instances.put("core-a", coreA);
        instances.put("core-b", coreB);

        StreamLifecycle lifecycle = coreA.open("trace-cluster", "tenant-a");
        AtomicInteger cancellations = new AtomicInteger();
        lifecycle.attachUpstreamCancellation(cancellations::incrementAndGet);

        StreamCancellationResult result = coreB.cancel("trace-cluster", "tenant-a");
        StreamCancellationResult duplicate = coreB.cancel("trace-cluster", "tenant-a");

        assertTrue(result.found());
        assertTrue(result.accepted());
        assertEquals(StreamTerminationReason.CLIENT_CANCEL_REQUESTED, result.state());
        assertTrue(duplicate.found());
        assertFalse(duplicate.accepted());
        assertEquals(1, cancellations.get());
    }

    @Test
    void hidesRemoteOwnerFromAnotherTenant() {
        Map<String, StreamOwnerDirectory.StreamOwner> owners = new ConcurrentHashMap<>();
        AtomicInteger forwarded = new AtomicInteger();
        StreamCancellationForwarder forwarder = (owner, traceId, tenantId) -> {
            forwarded.incrementAndGet();
            return StreamCancellationResult.notFound();
        };
        StreamCancellationCoordinator coreA = coordinator("core-a", 8081, owners, forwarder);
        StreamCancellationCoordinator coreB = coordinator("core-b", 8082, owners, forwarder);
        coreA.open("trace-private", "tenant-a");

        StreamCancellationResult result = coreB.cancel("trace-private", "tenant-b");

        assertFalse(result.found());
        assertEquals(0, forwarded.get());
    }

    @Test
    void rejectsClusterWideDuplicateTraceAndRollsBackLocalRegistration() {
        Map<String, StreamOwnerDirectory.StreamOwner> owners = new ConcurrentHashMap<>();
        StreamCancellationForwarder forwarder = (owner, traceId, tenantId) ->
                StreamCancellationResult.notFound();
        StreamCancellationCoordinator coreA = coordinator("core-a", 8081, owners, forwarder);
        StreamCancellationCoordinator coreB = coordinator("core-b", 8082, owners, forwarder);
        coreA.open("trace-duplicate", "tenant-a");

        assertThrows(StreamLifecycleConflictException.class,
                () -> coreB.open("trace-duplicate", "tenant-a"));
        assertFalse(coreB.cancelLocal("trace-duplicate", "tenant-a").found());
    }

    private StreamCancellationCoordinator coordinator(
            String instanceId,
            int port,
            Map<String, StreamOwnerDirectory.StreamOwner> owners,
            StreamCancellationForwarder forwarder
    ) {
        StreamOwnerDirectory directory = new StreamOwnerDirectory() {
            @Override
            public boolean register(String traceId, String tenantId) {
                return owners.putIfAbsent(traceId, localOwner(tenantId)) == null;
            }

            @Override
            public Optional<StreamOwner> find(String traceId) {
                return Optional.ofNullable(owners.get(traceId));
            }

            @Override
            public StreamOwner localOwner(String tenantId) {
                return new StreamOwner(instanceId, "http://127.0.0.1:" + port, tenantId);
            }
        };
        return new StreamCancellationCoordinator(new StreamLifecycleRegistry(), directory, forwarder);
    }
}
