package com.lingshu.core.billing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "lingshu.billing", name = "enabled", havingValue = "true")
public class BudgetExpiryScanner {

    private final BudgetLedger ledger;
    private final BudgetReservationService reservationService;

    @Autowired
    public BudgetExpiryScanner(
            BudgetLedger ledger,
            BudgetReservationService reservationService
    ) {
        this.ledger = ledger;
        this.reservationService = reservationService;
    }

    @Scheduled(fixedDelayString = "${LINGSHU_BILLING_EXPIRY_SCAN_INTERVAL_MS:30000}")
    public void releaseExpiredReservations() {
        for (String reservationId : ledger.findExpiredReservationIds(100)) {
            reservationService.release(reservationId);
        }
    }
}
