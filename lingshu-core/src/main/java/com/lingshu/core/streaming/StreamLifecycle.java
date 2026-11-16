package com.lingshu.core.streaming;

import com.lingshu.core.observability.StreamTerminationReason;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class StreamLifecycle {

    private final String traceId;
    private final String tenantId;
    private final AtomicReference<StreamTerminationReason> termination = new AtomicReference<>();
    private final AtomicReference<Runnable> cancelUpstream = new AtomicReference<>();
    private final AtomicLong terminatedAtNanos = new AtomicLong();

    StreamLifecycle(String traceId, String tenantId) {
        this.traceId = Objects.requireNonNull(traceId, "traceId must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    }

    public String traceId() {
        return traceId;
    }

    public String tenantId() {
        return tenantId;
    }

    public StreamTerminationReason termination() {
        return termination.get();
    }

    public boolean terminate(StreamTerminationReason reason, boolean cancelProvider) {
        Objects.requireNonNull(reason, "reason must not be null");
        boolean changed = termination.compareAndSet(null, reason);
        if (changed) {
            terminatedAtNanos.compareAndSet(0, System.nanoTime());
        }
        if (cancelProvider && (changed || termination.get() == reason)) {
            runCancellation();
        }
        return changed;
    }

    public void attachUpstreamCancellation(Runnable cancellation) {
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        cancelUpstream.set(cancellation);
        if (isCancelled()) {
            runCancellation();
        }
    }

    public void clearUpstreamCancellation() {
        cancelUpstream.set(null);
    }

    public boolean isCancelled() {
        StreamTerminationReason reason = termination.get();
        return reason != null && reason != StreamTerminationReason.COMPLETED;
    }

    long terminatedAtNanos() {
        return terminatedAtNanos.get();
    }

    private void runCancellation() {
        Runnable cancellation = cancelUpstream.getAndSet(null);
        if (cancellation != null) {
            try {
                cancellation.run();
            } catch (RuntimeException ignored) {
                // Termination state remains authoritative even if provider cleanup fails.
            }
        }
    }
}
