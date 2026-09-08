package com.lingshu.core.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.tenant-policy",
        name = "persistence-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class InMemoryTenantPolicyStore implements TenantPolicyStore {

    private final ConcurrentMap<String, TenantPolicy> policies = new ConcurrentHashMap<>();

    @Override
    public Optional<TenantPolicy> find(String tenantId) {
        return Optional.ofNullable(policies.get(tenantId));
    }

    @Override
    public TenantPolicy save(TenantPolicy policy) {
        policies.put(policy.tenantId(), policy);
        return policy;
    }
}
