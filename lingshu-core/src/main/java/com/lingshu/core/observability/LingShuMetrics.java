package com.lingshu.core.observability;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.core.billing.VirtualBillingCharge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LingShuMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeStreams = new AtomicInteger();

    public LingShuMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("lingshu.chat.streams.active", activeStreams);
    }

    public void success(
            String tenantId,
            String provider,
            String model,
            CacheStatus cacheStatus,
            long durationMs,
            VirtualBillingCharge billing
    ) {
        String cache = cacheStatus.name().toLowerCase();
        registry.counter("lingshu.chat.requests", "status", "success", "tenant", tenantId, "provider", provider,
                "model", model, "cache", cache).increment();
        registry.timer("lingshu.chat.duration", "status", "success", "tenant", tenantId, "provider", provider,
                "model", model).record(Duration.ofMillis(durationMs));
        registry.counter("lingshu.chat.tokens", "type", "input", "tenant", tenantId, "provider", provider)
                .increment(billing.inputTokens());
        registry.counter("lingshu.chat.tokens", "type", "output", "tenant", tenantId, "provider", provider)
                .increment(billing.outputTokens());
        registry.counter("lingshu.chat.virtual.cost.cny", "tenant", tenantId, "provider", provider)
                .increment(billing.costCny().doubleValue());
        registry.counter("lingshu.chat.usage.requests", "source",
                billing.usageSource().name().toLowerCase()).increment();
    }

    public void failure(String tenantId, String exceptionName, long durationMs, VirtualBillingCharge billing) {
        registry.counter("lingshu.chat.requests", "status", "failure", "tenant", tenantId,
                "exception", exceptionName).increment();
        registry.timer("lingshu.chat.duration", "status", "failure", "tenant", tenantId,
                        "exception", exceptionName)
                .record(Duration.ofMillis(durationMs));
        registry.counter("lingshu.chat.tokens", "type", "failed_input", "tenant", tenantId,
                        "provider", "unknown")
                .increment(billing.inputTokens());
        registry.counter("lingshu.chat.tokens", "type", "failed_output", "tenant", tenantId,
                        "provider", "unknown")
                .increment(billing.outputTokens());
        registry.counter("lingshu.chat.virtual.cost.cny", "tenant", tenantId, "provider", "failed")
                .increment(billing.costCny().doubleValue());
    }

    public void timeToFirstToken(
            String tenantId,
            String provider,
            String model,
            CacheStatus cacheStatus,
            long durationNanos
    ) {
        registry.timer("lingshu.chat.ttft", "tenant", tenantId, "provider", provider,
                        "model", model, "cache", cacheStatus.name().toLowerCase())
                .record(Duration.ofNanos(Math.max(1, durationNanos)));
    }

    public void streamStarted() {
        activeStreams.incrementAndGet();
    }

    public void streamFinished(StreamTerminationReason reason, long durationNanos) {
        activeStreams.updateAndGet(value -> Math.max(0, value - 1));
        String outcome = reason.name().toLowerCase();
        registry.counter("lingshu.chat.streams.terminated", "reason", outcome).increment();
        registry.timer("lingshu.chat.stream.duration", "reason", outcome)
                .record(Duration.ofNanos(Math.max(1, durationNanos)));
    }
}
