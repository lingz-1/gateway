package com.lingshu.core.billing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "lingshu.billing.outbox", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "lingshu.kafka", name = "enabled", havingValue = "true")
public class BudgetOutboxRelay {

    private final BudgetOutboxStore outboxStore;
    private final BudgetEventPublisher eventPublisher;
    private final int batchSize;

    @Autowired
    public BudgetOutboxRelay(
            BudgetOutboxStore outboxStore,
            BudgetEventPublisher eventPublisher,
            com.lingshu.core.config.LingShuProperties properties
    ) {
        this.outboxStore = outboxStore;
        this.eventPublisher = eventPublisher;
        this.batchSize = properties.getBilling().getOutbox().getBatchSize();
    }

    @Scheduled(fixedDelayString = "${LINGSHU_BILLING_OUTBOX_POLL_INTERVAL_MS:5000}")
    public void relay() {
        List<BudgetOutboxEvent> events = outboxStore.claimUnpublished(batchSize);
        for (BudgetOutboxEvent event : events) {
            eventPublisher.publish(event);
            outboxStore.markPublished(event);
        }
    }
}
