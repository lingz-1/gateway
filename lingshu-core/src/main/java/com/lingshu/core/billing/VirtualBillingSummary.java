package com.lingshu.core.billing;

import java.math.BigDecimal;

public record VirtualBillingSummary(
        String tenantId,
        BigDecimal balanceCny,
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        long cacheHits,
        long inputTokens,
        long outputTokens,
        BigDecimal totalCostCny
) {
}
