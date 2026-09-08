package com.lingshu.core.processing;

import com.lingshu.core.tenant.TenantPolicy;
import com.lingshu.core.tenant.TenantPolicyService;
import com.lingshu.core.tenant.TenantPolicyViolationException;
import com.lingshu.core.tenant.TenantRequestGuard;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(125)
public class TenantPolicyProcessor implements ChatProcessor {

    private final TenantPolicyService policyService;
    private final TenantRequestGuard requestGuard;

    public TenantPolicyProcessor(TenantPolicyService policyService, TenantRequestGuard requestGuard) {
        this.policyService = policyService;
        this.requestGuard = requestGuard;
    }

    @Override
    public String name() {
        return "tenant-policy";
    }

    @Override
    public void process(ChatProcessingContext context) {
        TenantPolicy policy = policyService.resolve(context.tenantId());
        if (!policy.enabled()) {
            throw new TenantPolicyViolationException("Tenant is disabled");
        }
        if (!policy.allowsModel(context.request().model())) {
            throw new TenantPolicyViolationException("Model is not allowed for tenant: " + context.request().model());
        }
        context.tenantPolicy(policy);
        context.policyPermit(requestGuard.acquire(policy));
    }
}
