package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;

import java.time.Instant;
import java.util.Objects;

public record BudgetOutboxEvent(
        long eventId,
        String reservationId,
        String eventType,
        String tenantId,
        long units,
        TokenUsageSource usageSource,
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
        usageSource = usageSource == null ? TokenUsageSource.UNKNOWN : usageSource;
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (reservationId.isBlank() || eventType.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("outbox event identifiers must not be blank");
        }
        if (units < 0) {
            throw new IllegalArgumentException("outbox event units must not be negative");
        }
    }

    public BudgetOutboxEvent(
            long eventId,
            String reservationId,
            String eventType,
            String tenantId,
            long units,
            Instant createdAt,
            String claimToken
    ) {
        this(eventId, reservationId, eventType, tenantId, units,
                TokenUsageSource.UNKNOWN, createdAt, claimToken);
    }
}
