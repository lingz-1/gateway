package com.lingshu.core.tenant;

public interface TenantPolicyStore extends TenantPolicySource {

    TenantPolicy save(TenantPolicy policy);
}
