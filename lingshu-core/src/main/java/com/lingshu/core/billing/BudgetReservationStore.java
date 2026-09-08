package com.lingshu.core.billing;

public interface BudgetReservationStore {

    boolean reserve(String tenantId, String reservationId, long units);

    boolean confirm(String reservationId);

    boolean release(String reservationId);
}
