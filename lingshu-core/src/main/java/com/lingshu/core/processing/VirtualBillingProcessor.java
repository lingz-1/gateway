package com.lingshu.core.processing;

import com.lingshu.core.billing.VirtualBillingService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(450)
public class VirtualBillingProcessor implements ChatProcessor {

    private final VirtualBillingService billingService;

    public VirtualBillingProcessor(VirtualBillingService billingService) {
        this.billingService = billingService;
    }

    @Override
    public String name() {
        return "virtual-billing";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return context.completed();
    }

    @Override
    public void process(ChatProcessingContext context) {
        context.virtualBillingCharge(billingService.charge(
                context.tenantId(),
                context.traceId(),
                context.providerResponse(),
                context.cacheStatus()
        ));
    }
}
