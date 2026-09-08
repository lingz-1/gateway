package com.lingshu.core.processing;

public record ChatProcessingResult(
        ChatProcessingContext context,
        long totalDurationMs
) {
}
