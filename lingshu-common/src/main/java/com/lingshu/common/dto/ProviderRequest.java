package com.lingshu.common.dto;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record ProviderRequest(
        String traceId,
        String tenantId,
        String requestedModel,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        Double topP
) {
    public ProviderRequest {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(requestedModel, "requestedModel must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (topP != null && (topP < 0.0 || topP > 1.0)) {
            throw new IllegalArgumentException("topP must be between 0 and 1");
        }
    }

    public String prompt() {
        return messages.stream()
                .map(message -> message.role() + ":" + message.content())
                .collect(Collectors.joining("\n"));
    }
}
