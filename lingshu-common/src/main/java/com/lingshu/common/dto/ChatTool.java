package com.lingshu.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ChatTool(
        @NotBlank @Pattern(regexp = "function") String type,
        @NotNull @Valid ChatFunctionDefinition function
) {
}
