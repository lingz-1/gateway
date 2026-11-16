package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;

public interface BudgetReservationStore {

    boolean reserve(String tenantId, String reservationId, long units);

    boolean confirm(String reservationId);

    boolean settle(String reservationId, long actualUnits, TokenUsageSource usageSource);

    boolean release(String reservationId);
}
