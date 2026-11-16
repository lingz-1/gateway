package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;

import java.util.Objects;

public record BudgetSettlement(
        String reservationId,
        String tenantId,
        long reservedUnits,
        long actualUnits,
        long releasedUnits,
        TokenUsageSource usageSource
) {
    public BudgetSettlement {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(usageSource, "usageSource must not be null");
        if (reservationId.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("settlement identifiers must not be blank");
        }
        if (reservedUnits <= 0 || actualUnits < 0) {
            throw new IllegalArgumentException("settlement units are invalid");
        }
        if (releasedUnits != reservedUnits - actualUnits) {
            throw new IllegalArgumentException("released units must equal reserved minus actual");
        }
    }

    public boolean overage() {
        return releasedUnits < 0;
    }
}
