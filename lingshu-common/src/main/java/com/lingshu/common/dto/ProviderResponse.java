package com.lingshu.common.dto;

import java.util.List;
import java.util.Objects;

public record ProviderResponse(
        String provider,
        String model,
        String content,
        int inputTokens,
        int outputTokens,
        TokenUsageSource usageSource,
        String finishReason,
        List<ChatToolCall> toolCalls
) {
    public ProviderResponse {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        if ((content == null || content.isBlank()) && (toolCalls == null || toolCalls.isEmpty())) {
            throw new IllegalArgumentException("content or toolCalls must be present");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        usageSource = usageSource == null ? TokenUsageSource.UNKNOWN : usageSource;
        if (finishReason == null || finishReason.isBlank()) {
            finishReason = "stop";
        }
    }

    public ProviderResponse(
            String provider,
            String model,
            String content,
            int inputTokens,
            int outputTokens,
            String finishReason,
            List<ChatToolCall> toolCalls
    ) {
        this(provider, model, content, inputTokens, outputTokens,
                TokenUsageSource.PROVIDER_REPORTED, finishReason, toolCalls);
    }

    public ProviderResponse(
            String provider,
            String model,
            String content,
            int inputTokens,
            int outputTokens,
            String finishReason
    ) {
        this(provider, model, content, inputTokens, outputTokens,
                TokenUsageSource.PROVIDER_REPORTED, finishReason, List.of());
    }

    public ProviderResponse(
            String provider,
            String model,
            String content,
            int inputTokens,
            int outputTokens
    ) {
        this(provider, model, content, inputTokens, outputTokens,
                TokenUsageSource.PROVIDER_REPORTED, "stop", List.of());
    }
}
