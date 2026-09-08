package com.lingshu.common.dto;

import java.util.Objects;

public record ProviderResponse(
        String provider,
        String model,
        String content,
        int inputTokens,
        int outputTokens
) {
    public ProviderResponse {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
