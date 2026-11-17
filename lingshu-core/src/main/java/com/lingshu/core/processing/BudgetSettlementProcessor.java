package com.lingshu.core.processing;

import com.lingshu.core.billing.BudgetRequestCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(425)
@ConditionalOnProperty(prefix = "lingshu.billing", name = "enabled", havingValue = "true")
public class BudgetSettlementProcessor implements ChatProcessor {

    private final BudgetRequestCoordinator coordinator;

    public BudgetSettlementProcessor(BudgetRequestCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public String name() {
        return "budget-settlement";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return context.completed() && context.budgetReservation() != null;
    }

    @Override
    public void process(ChatProcessingContext context) {
        if (!context.beginBudgetFinalization()) {
            return;
        }
        coordinator.settle(
                context.budgetReservation(),
                context.request(),
                context.providerResponse(),
                context.cacheStatus()
        );
        context.budgetFinalized();
    }
}
