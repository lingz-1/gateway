package com.lingshu.core.billing;

import java.time.Instant;
import java.util.Objects;

public record BudgetReservation(
        String reservationId,
        String tenantId,
        long units,
        Instant expiresAt,
        BudgetReservationStatus status
) {
    public BudgetReservation {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (reservationId.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("reservationId and tenantId must not be blank");
        }
        if (units <= 0) {
            throw new IllegalArgumentException("reservation units must be positive");
        }
    }
}
