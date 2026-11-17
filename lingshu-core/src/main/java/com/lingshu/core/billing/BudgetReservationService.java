package com.lingshu.core.billing;

import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.common.dto.TokenUsageSource;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@ConditionalOnProperty(
        prefix = "lingshu.billing",
        name = "enabled",
        havingValue = "true"
)
public class BudgetReservationService {

    private final BudgetReservationStore reservationStore;
    private final BudgetLedger ledger;
    private final Duration reservationTtl;

    @Autowired
    public BudgetReservationService(
            BudgetReservationStore reservationStore,
            BudgetLedger ledger,
            LingShuProperties properties
    ) {
        this.reservationStore = reservationStore;
        this.ledger = ledger;
        this.reservationTtl = properties.getBilling().getReservationTtl();
        if (reservationTtl == null || reservationTtl.isZero() || reservationTtl.isNegative()) {
            throw new IllegalArgumentException("Billing reservation TTL must be positive");
        }
    }

    public BudgetReservation reserve(String tenantId, String reservationId, long units) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        if (units <= 0) {
            throw new IllegalArgumentException("reservation units must be positive");
        }
        if (!reservationStore.reserve(tenantId, reservationId, units)) {
            throw new BudgetExceededException(tenantId);
        }
        BudgetReservation reservation = new BudgetReservation(
                reservationId,
                tenantId,
                units,
                Instant.now().plus(reservationTtl),
                BudgetReservationStatus.PENDING
        );
        try {
            ledger.recordReservation(reservation);
            return reservation;
        } catch (RuntimeException exception) {
            reservationStore.release(reservationId);
            throw exception;
        }
    }

    public boolean confirm(String reservationId) {
        boolean recorded = ledger.confirm(reservationId);
        if (!recorded) {
            return false;
        }
        reservationStore.confirm(reservationId);
        return true;
    }

    public BudgetSettlement settle(String reservationId, ProviderResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        return settle(
                reservationId,
                (long) response.inputTokens() + response.outputTokens(),
                response.usageSource()
        );
    }

    public BudgetSettlement settle(
            String reservationId,
            long actualUnits,
            TokenUsageSource usageSource
    ) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        if (actualUnits < 0) {
            throw new IllegalArgumentException("actual units must not be negative");
        }
        if (usageSource != TokenUsageSource.PROVIDER_REPORTED
                && usageSource != TokenUsageSource.ESTIMATED
                && usageSource != TokenUsageSource.MIXED) {
            throw new IllegalArgumentException("usageSource must describe actual or estimated usage");
        }
        BudgetSettlement settlement = ledger.settle(reservationId, actualUnits, usageSource)
                .orElseThrow(() -> new IllegalStateException(
                        "Budget reservation cannot be settled or conflicts with an existing settlement"
                ));
        if (!reservationStore.settle(reservationId, actualUnits, usageSource)) {
            throw new IllegalStateException(
                    "Budget ledger settled but Redis balance has not converged; retry the same settlement"
            );
        }
        return settlement;
    }

    public boolean release(String reservationId) {
        boolean recorded = ledger.release(reservationId);
        boolean released = reservationStore.release(reservationId);
        return recorded || released;
    }
}
