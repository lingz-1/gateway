package com.lingshu.core.billing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VirtualBillingServiceTest {

    @Test
    void chargesSuccessfulProviderMissByActualTokens() {
        VirtualBillingService service = new VirtualBillingService(new LingShuProperties());

        VirtualBillingCharge charge = service.charge(
                "tenant-a",
                "trace-a",
                new ProviderResponse("deepseek", "deepseek-v4flash", "ok", 7, 4),
                CacheStatus.MISS
        );

        assertEquals(0.000030096, charge.costCny().doubleValue(), 0.000000001);
        assertEquals(9.999969904, charge.remainingBalanceCny().doubleValue(), 0.000000001);
        assertEquals(charge, service.chargeForTrace("trace-a"));
    }

    @Test
    void cacheHitsConsumeNoVirtualModelCost() {
        VirtualBillingService service = new VirtualBillingService(new LingShuProperties());

        VirtualBillingCharge charge = service.charge(
                "tenant-a",
                "trace-cache",
                new ProviderResponse("deepseek", "deepseek-v4flash", "cached", 7, 4),
                CacheStatus.SEMANTIC
        );

        assertEquals(0.0, charge.costCny().doubleValue());
        assertEquals(10.0, charge.remainingBalanceCny().doubleValue(), 0.000001);
    }

    @Test
    void failedProviderUsageIsChargedWhenUsageIsKnown() {
        VirtualBillingService service = new VirtualBillingService(new LingShuProperties());

        VirtualBillingCharge charge = service.recordFailure("tenant-a", "trace-failed", 100, 50);

        assertEquals(0.000396, charge.costCny().doubleValue(), 0.000000001);
        assertEquals(9.999604, charge.remainingBalanceCny().doubleValue(), 0.000001);
    }
}
