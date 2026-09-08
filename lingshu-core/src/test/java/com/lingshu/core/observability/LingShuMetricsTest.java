package com.lingshu.core.observability;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.core.billing.VirtualBillingCharge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LingShuMetricsTest {

    @Test
    void separatesSuccessMetricsByTenant() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LingShuMetrics metrics = new LingShuMetrics(registry);

        metrics.success("tenant-a", "stub", "stub-echo-v1", CacheStatus.MISS, 12, charge());
        metrics.success("tenant-b", "stub", "stub-echo-v1", CacheStatus.MISS, 8, charge());
        metrics.success("tenant-a", "stub", "stub-echo-v1", CacheStatus.MISS, 4, charge());

        assertEquals(2.0, registry.get("lingshu.chat.requests")
                .tags("status", "success", "tenant", "tenant-a", "provider", "stub",
                        "model", "stub-echo-v1", "cache", "miss")
                .counter().count());
        assertEquals(1.0, registry.get("lingshu.chat.requests")
                .tags("status", "success", "tenant", "tenant-b", "provider", "stub",
                        "model", "stub-echo-v1", "cache", "miss")
                .counter().count());
        assertEquals(6.0, registry.get("lingshu.chat.tokens")
                .tags("type", "input", "tenant", "tenant-a", "provider", "stub")
                .counter().count());
    }

    @Test
    void recordsFailureMetricsForTenant() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LingShuMetrics metrics = new LingShuMetrics(registry);

        metrics.failure("tenant-failed", "ProviderRoutingException", 20, charge());

        assertEquals(1.0, registry.get("lingshu.chat.requests")
                .tags("status", "failure", "tenant", "tenant-failed",
                        "exception", "ProviderRoutingException")
                .counter().count());
    }

    private VirtualBillingCharge charge() {
        return new VirtualBillingCharge(
                new BigDecimal("0.01"),
                new BigDecimal("9.99"),
                3,
                5
        );
    }
}
