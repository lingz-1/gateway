package com.lingshu.core.billing;

import java.time.Instant;
import java.util.Objects;

public record BudgetOutboxEvent(
        long eventId,
        String reservationId,
        String eventType,
        String tenantId,
        long units,
        Instant createdAt,
        String claimToken
) {

    public BudgetOutboxEvent {
        if (eventId <= 0) {
            throw new IllegalArgumentException("eventId must be positive");
        }
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (reservationId.isBlank() || eventType.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("outbox event identifiers must not be blank");
        }
        if (units <= 0) {
            throw new IllegalArgumentException("outbox event units must be positive");
        }
    }
}
