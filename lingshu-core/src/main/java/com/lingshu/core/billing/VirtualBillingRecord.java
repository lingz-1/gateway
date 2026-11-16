package com.lingshu.core.billing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.common.dto.TokenUsageSource;

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
        TokenUsageSource usageSource,
        BigDecimal costCny,
        BigDecimal remainingBalanceCny,
        Instant createdAt
) {
    public VirtualBillingRecord(
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
        this(traceId, tenantId, provider, model, cacheStatus, status, inputTokens, outputTokens,
                TokenUsageSource.UNKNOWN, costCny, remainingBalanceCny, createdAt);
    }
}
