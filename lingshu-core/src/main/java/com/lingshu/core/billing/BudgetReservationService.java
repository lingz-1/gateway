package com.lingshu.core.billing;

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

    public boolean release(String reservationId) {
        boolean recorded = ledger.release(reservationId);
        if (!recorded) {
            return false;
        }
        reservationStore.release(reservationId);
        return true;
    }
}
