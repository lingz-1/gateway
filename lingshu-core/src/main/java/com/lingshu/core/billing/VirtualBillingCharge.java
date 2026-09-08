package com.lingshu.core.billing;

import java.math.BigDecimal;
import java.util.Objects;

public record VirtualBillingCharge(
        BigDecimal costCny,
        BigDecimal remainingBalanceCny,
        int inputTokens,
        int outputTokens
) {

    public VirtualBillingCharge {
        Objects.requireNonNull(costCny, "costCny must not be null");
        Objects.requireNonNull(remainingBalanceCny, "remainingBalanceCny must not be null");
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
