package com.lingshu.core.billing;

import com.lingshu.common.dto.CacheStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record VirtualBillingRecord(
        String traceId,
        String tenantId,
        String provider,
        String model,
        CacheStatus cacheStatus,
        String status,
        int inputTokens,
        int outputTokens,
        BigDecimal costCny,
        BigDecimal remainingBalanceCny,
        Instant createdAt
) {
}
