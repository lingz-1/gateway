package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;

import java.math.BigDecimal;
import java.util.Objects;

public record VirtualBillingCharge(
        BigDecimal costCny,
        BigDecimal remainingBalanceCny,
        int inputTokens,
        int outputTokens,
        TokenUsageSource usageSource
) {

    public VirtualBillingCharge {
        Objects.requireNonNull(costCny, "costCny must not be null");
        Objects.requireNonNull(remainingBalanceCny, "remainingBalanceCny must not be null");
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        usageSource = usageSource == null ? TokenUsageSource.UNKNOWN : usageSource;
    }

    public VirtualBillingCharge(
            BigDecimal costCny,
            BigDecimal remainingBalanceCny,
            int inputTokens,
            int outputTokens
    ) {
        this(costCny, remainingBalanceCny, inputTokens, outputTokens, TokenUsageSource.UNKNOWN);
    }
}
