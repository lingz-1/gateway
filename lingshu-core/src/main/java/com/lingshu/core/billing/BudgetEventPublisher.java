package com.lingshu.core.billing;

public interface BudgetEventPublisher {

    void publish(BudgetOutboxEvent event);
}
