package com.lingshu.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.stream.Collectors;

public record ChatCompletionRequest(
        @NotBlank String model,
        @NotEmpty List<@Valid ChatMessage> messages,
        Boolean stream,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature
) {

    public String prompt() {
        return messages.stream()
                .map(message -> message.role() + ":" + message.content())
                .collect(Collectors.joining("\n"));
    }
}
