package com.lingshu.core.tenant;

import java.util.Optional;

public interface TenantPolicySource {

    Optional<TenantPolicy> find(String tenantId);
}
