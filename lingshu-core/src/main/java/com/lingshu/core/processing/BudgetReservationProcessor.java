package com.lingshu.core.processing;

import com.lingshu.core.billing.BudgetRequestCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(250)
@ConditionalOnProperty(prefix = "lingshu.billing", name = "enabled", havingValue = "true")
public class BudgetReservationProcessor implements ChatProcessor {

    private final BudgetRequestCoordinator coordinator;

    public BudgetReservationProcessor(BudgetRequestCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public String name() {
        return "budget-reservation";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return !context.completed();
    }

    @Override
    public void process(ChatProcessingContext context) {
        context.budgetReservation(coordinator.reserve(
                context.request(),
                context.traceId(),
                context.tenantId()
        ));
    }
}
