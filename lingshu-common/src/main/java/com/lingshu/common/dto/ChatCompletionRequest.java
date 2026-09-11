package com.lingshu.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.stream.Collectors;

public record ChatCompletionRequest(
        @NotBlank String model,
        @NotEmpty List<@Valid ChatMessage> messages,
        Boolean stream,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @Positive Integer max_tokens,
        @DecimalMin("0.0") @DecimalMax("1.0") Double top_p,
        Long seed,
        @DecimalMin("-2.0") @DecimalMax("2.0") Double frequency_penalty,
        @DecimalMin("-2.0") @DecimalMax("2.0") Double presence_penalty,
        List<@Valid ChatTool> tools,
        Object tool_choice
) {

    public ChatCompletionRequest {
        if (tools != null) {
            tools = List.copyOf(tools);
        }
    }

    public ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            Boolean stream,
            Double temperature,
            Integer max_tokens,
            Double top_p
    ) {
        this(model, messages, stream, temperature, max_tokens, top_p, null, null, null, null, null);
    }

    public ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            Boolean stream,
            Double temperature,
            Integer max_tokens,
            Double top_p,
            Long seed,
            Double frequency_penalty,
            Double presence_penalty
    ) {
        this(model, messages, stream, temperature, max_tokens, top_p,
                seed, frequency_penalty, presence_penalty, null, null);
    }

    public String prompt() {
        return messages.stream()
                .map(ChatMessage::promptText)
                .collect(Collectors.joining("\n"));
    }
}
