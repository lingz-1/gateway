package com.lingshu.gateway.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lingshu.gateway.request-policy")
public class RequestPolicyProperties {

    private boolean tenantIdRequired = true;

    @Positive
    private int maxBodyBytes = 20 * 1024 * 1024;

    public boolean isTenantIdRequired() {
        return tenantIdRequired;
    }

    public void setTenantIdRequired(boolean tenantIdRequired) {
        this.tenantIdRequired = tenantIdRequired;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }
}
