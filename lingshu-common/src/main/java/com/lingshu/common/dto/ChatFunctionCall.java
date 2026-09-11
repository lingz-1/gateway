package com.lingshu.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatFunctionCall(
        @NotBlank String name,
        @NotNull String arguments
) {
}
