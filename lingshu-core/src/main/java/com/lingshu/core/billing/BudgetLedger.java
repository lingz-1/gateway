package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;

import java.util.List;
import java.util.Optional;

public interface BudgetLedger {

    void recordReservation(BudgetReservation reservation);

    boolean confirm(String reservationId);

    Optional<BudgetSettlement> settle(
            String reservationId,
            long actualUnits,
            TokenUsageSource usageSource
    );

    boolean release(String reservationId);

    default List<String> findExpiredReservationIds(int limit) {
        return List.of();
    }
}
