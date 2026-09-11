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
        Double topP,
        Long seed,
        Double frequencyPenalty,
        Double presencePenalty,
        List<ChatTool> tools,
        Object toolChoice
) {
    public ProviderRequest(
            String traceId,
            String tenantId,
            String requestedModel,
            List<ChatMessage> messages,
            Double temperature,
            Integer maxTokens,
            Double topP
    ) {
        this(traceId, tenantId, requestedModel, messages, temperature, maxTokens, topP,
                null, null, null, null, null);
    }

    public ProviderRequest(
            String traceId,
            String tenantId,
            String requestedModel,
            List<ChatMessage> messages,
            Double temperature,
            Integer maxTokens,
            Double topP,
            Long seed,
            Double frequencyPenalty,
            Double presencePenalty
    ) {
        this(traceId, tenantId, requestedModel, messages, temperature, maxTokens, topP,
                seed, frequencyPenalty, presencePenalty, null, null);
    }

    public ProviderRequest {
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(requestedModel, "requestedModel must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        if (tools != null) {
            tools = List.copyOf(tools);
        }
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (topP != null && (topP < 0.0 || topP > 1.0)) {
            throw new IllegalArgumentException("topP must be between 0 and 1");
        }
        if (frequencyPenalty != null && (frequencyPenalty < -2.0 || frequencyPenalty > 2.0)) {
            throw new IllegalArgumentException("frequencyPenalty must be between -2 and 2");
        }
        if (presencePenalty != null && (presencePenalty < -2.0 || presencePenalty > 2.0)) {
            throw new IllegalArgumentException("presencePenalty must be between -2 and 2");
        }
    }

    public String prompt() {
        return messages.stream().map(message -> {
            StringBuilder value = new StringBuilder(message.role()).append(':');
            if (message.content() != null) {
                value.append(message.content());
            }
            if (message.tool_call_id() != null) {
                value.append("|tool_call_id=").append(message.tool_call_id());
            }
            if (message.tool_calls() != null) {
                message.tool_calls().forEach(call -> value
                        .append("|tool_call=").append(call.id())
                        .append(':').append(call.function().name())
                        .append(':').append(call.function().arguments()));
            }
            return value.toString();
        }).collect(Collectors.joining("\n"));
    }
}
