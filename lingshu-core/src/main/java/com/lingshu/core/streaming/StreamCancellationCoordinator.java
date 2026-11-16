package com.lingshu.core.streaming;

import com.lingshu.core.observability.StreamTerminationReason;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class StreamCancellationCoordinator {

    private final StreamLifecycleRegistry registry;
    private final StreamOwnerDirectory ownerDirectory;
    private final StreamCancellationForwarder forwarder;

    public StreamCancellationCoordinator(
            StreamLifecycleRegistry registry,
            StreamOwnerDirectory ownerDirectory,
            StreamCancellationForwarder forwarder
    ) {
        this.registry = registry;
        this.ownerDirectory = ownerDirectory;
        this.forwarder = forwarder;
    }

    public StreamLifecycle open(String traceId, String tenantId) {
        StreamLifecycle lifecycle = registry.open(traceId, tenantId);
        try {
            if (!ownerDirectory.register(traceId, tenantId)) {
                registry.discard(lifecycle);
                throw new StreamLifecycleConflictException(
                        "Stream traceId is already registered by another Core instance: " + traceId
                );
            }
            return lifecycle;
        } catch (RuntimeException exception) {
            registry.discard(lifecycle);
            throw exception;
        }
    }

    public StreamTerminationReason finish(
            StreamLifecycle lifecycle,
            StreamTerminationReason proposedReason
    ) {
        return registry.finish(lifecycle, proposedReason);
    }

    public StreamCancellationResult cancel(String traceId, String tenantId) {
        StreamCancellationResult local = cancelLocal(traceId, tenantId);
        if (local.found()) {
            return local;
        }
        Optional<StreamOwnerDirectory.StreamOwner> located = ownerDirectory.find(traceId);
        if (located.isEmpty() || !located.get().tenantId().equals(tenantId)) {
            return StreamCancellationResult.notFound();
        }
        StreamOwnerDirectory.StreamOwner owner = located.get();
        StreamOwnerDirectory.StreamOwner current = ownerDirectory.localOwner(tenantId);
        if (owner.instanceId().equals(current.instanceId())) {
            return cancelLocal(traceId, tenantId);
        }
        return forwarder.forward(owner, traceId, tenantId);
    }

    public StreamCancellationResult cancelLocal(String traceId, String tenantId) {
        StreamLifecycleRegistry.CancellationResult result = registry.requestCancellation(traceId, tenantId);
        return new StreamCancellationResult(result.found(), result.accepted(), result.state());
    }
}
