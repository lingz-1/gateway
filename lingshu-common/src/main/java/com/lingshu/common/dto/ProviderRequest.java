package com.lingshu.common.dto;

import java.util.Objects;

public record ProviderRequest(
        String traceId,
        String tenantId,
        String requestedModel,
        String prompt
) {
    public ProviderRequest {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(requestedModel, "requestedModel must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");
    }
}
