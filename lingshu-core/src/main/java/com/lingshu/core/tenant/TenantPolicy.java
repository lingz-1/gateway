package com.lingshu.core.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record TenantPolicy(
        String tenantId,
        boolean enabled,
        Set<String> allowedModels,
        boolean piiRedactionEnabled,
        boolean exactCacheEnabled,
        boolean semanticCacheEnabled,
        int requestsPerMinute,
        int maxConcurrentRequests,
        BigDecimal inputPriceUsdPerMillion,
        BigDecimal outputPriceUsdPerMillion,
        Instant updatedAt
) {
    public TenantPolicy {
        allowedModels = allowedModels == null ? Set.of() : Set.copyOf(allowedModels);
        if (requestsPerMinute < 0 || maxConcurrentRequests < 0) {
            throw new IllegalArgumentException("Tenant request limits must not be negative");
        }
        if (inputPriceUsdPerMillion == null || inputPriceUsdPerMillion.signum() < 0
                || outputPriceUsdPerMillion == null || outputPriceUsdPerMillion.signum() < 0) {
            throw new IllegalArgumentException("Tenant model prices must not be negative");
        }
    }

    public boolean allowsModel(String model) {
        return allowedModels.isEmpty() || allowedModels.contains(model);
    }
}
