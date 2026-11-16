package com.lingshu.core.streaming;

import com.lingshu.core.observability.StreamTerminationReason;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class StreamLifecycleRegistry {

    private static final long RETENTION_NANOS = Duration.ofMinutes(5).toNanos();
    private static final long CLEANUP_INTERVAL = 1_024;
    private static final int MAX_RETAINED_STREAMS = 10_000;

    private final ConcurrentMap<String, StreamLifecycle> streams = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    public StreamLifecycle open(String traceId, String tenantId) {
        cleanupIfNeeded();
        StreamLifecycle lifecycle = new StreamLifecycle(traceId, tenantId);
        StreamLifecycle existing = streams.putIfAbsent(traceId, lifecycle);
        if (existing != null) {
            throw new StreamLifecycleConflictException("Stream traceId is already registered: " + traceId);
        }
        return lifecycle;
    }

    public CancellationResult requestCancellation(String traceId, String tenantId) {
        cleanupIfNeeded();
        StreamLifecycle lifecycle = streams.get(traceId);
        if (lifecycle == null || !lifecycle.tenantId().equals(tenantId)) {
            return new CancellationResult(false, false, null);
        }
        boolean accepted = lifecycle.terminate(StreamTerminationReason.CLIENT_CANCEL_REQUESTED, true);
        return new CancellationResult(true, accepted, lifecycle.termination());
    }

    public StreamTerminationReason finish(
            StreamLifecycle lifecycle,
            StreamTerminationReason proposedReason
    ) {
        lifecycle.terminate(proposedReason, false);
        cleanupIfNeeded();
        return lifecycle.termination();
    }

    public void discard(StreamLifecycle lifecycle) {
        streams.remove(lifecycle.traceId(), lifecycle);
    }

    private void cleanupIfNeeded() {
        boolean periodicCleanup = operations.incrementAndGet() % CLEANUP_INTERVAL == 0;
        if (!periodicCleanup && streams.size() <= MAX_RETAINED_STREAMS) {
            return;
        }
        if (periodicCleanup) {
            long cutoff = System.nanoTime() - RETENTION_NANOS;
            streams.entrySet().removeIf(entry -> {
                long terminatedAt = entry.getValue().terminatedAtNanos();
                return terminatedAt > 0 && terminatedAt < cutoff;
            });
        }
        int overflow = streams.size() - MAX_RETAINED_STREAMS;
        if (overflow <= 0) {
            return;
        }
        for (var entry : streams.entrySet()) {
            if (overflow <= 0) {
                break;
            }
            if (entry.getValue().termination() != null
                    && streams.remove(entry.getKey(), entry.getValue())) {
                overflow--;
            }
        }
    }

    int retainedCount() {
        return streams.size();
    }

    public record CancellationResult(
            boolean found,
            boolean accepted,
            StreamTerminationReason state
    ) {
    }
}
