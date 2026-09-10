package com.lingshu.core.processing;

public record ChatStreamingExecution(
        ChatProcessingContext context,
        long startedAtNanos,
        int nextProcessorIndex,
        ChatProcessingResult completedResult
) {

    public boolean completed() {
        return completedResult != null;
    }
}
