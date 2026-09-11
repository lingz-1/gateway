package com.lingshu.common.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ChatFunctionDefinition(
        @NotBlank String name,
        String description,
        Map<String, Object> parameters,
        Boolean strict
) {
    public ChatFunctionDefinition {
        if (parameters != null) {
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
    }
}
