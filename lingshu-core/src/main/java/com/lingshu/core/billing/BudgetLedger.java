package com.lingshu.core.billing;

import java.util.List;

public interface BudgetLedger {

    void recordReservation(BudgetReservation reservation);

    boolean confirm(String reservationId);

    boolean release(String reservationId);

    default List<String> findExpiredReservationIds(int limit) {
        return List.of();
    }
}
