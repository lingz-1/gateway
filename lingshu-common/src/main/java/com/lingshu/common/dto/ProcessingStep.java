package com.lingshu.common.dto;

public record ProcessingStep(
        String name,
        long durationMs
) {
}
