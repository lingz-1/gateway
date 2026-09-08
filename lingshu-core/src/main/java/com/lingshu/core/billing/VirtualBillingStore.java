package com.lingshu.core.billing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface VirtualBillingStore {

    BigDecimal getOrCreateBalance(String tenantId, BigDecimal initialBalanceCny);

    VirtualBillingRecord recordAtomically(VirtualBillingRecord proposed, BigDecimal initialBalanceCny);

    Optional<VirtualBillingRecord> findByTraceId(String traceId);

    VirtualBillingSummary summary(String tenantId, BigDecimal fallbackBalanceCny);

    List<VirtualBillingRecord> recent(String tenantId, int limit);
}
