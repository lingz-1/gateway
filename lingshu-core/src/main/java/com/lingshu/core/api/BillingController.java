package com.lingshu.core.api;

import com.lingshu.core.billing.VirtualBillingRecord;
import com.lingshu.core.billing.VirtualBillingService;
import com.lingshu.core.billing.VirtualBillingSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/billing/tenants")
public class BillingController {

    private final VirtualBillingService billingService;

    public BillingController(VirtualBillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/{tenantId}")
    public VirtualBillingSummary summary(@PathVariable String tenantId) {
        return billingService.summary(tenantId);
    }

    @GetMapping("/{tenantId}/usage")
    public List<VirtualBillingRecord> usage(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return billingService.recent(tenantId, limit);
    }
}
