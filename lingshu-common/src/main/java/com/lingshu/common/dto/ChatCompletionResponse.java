package com.lingshu.common.dto;

import java.math.BigDecimal;
import java.util.List;

public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage,
        Metadata metadata
) {

    public record Choice(
            int index,
            ChatMessage message,
            String finish_reason
    ) {
    }

    public record Usage(
            int prompt_tokens,
            int completion_tokens,
            int total_tokens
    ) {
    }

    public record Metadata(
            String traceId,
            String tenantId,
            String provider,
            CacheStatus cacheStatus,
            long totalDurationMs,
            List<ProcessingStep> processors,
            BigDecimal virtualCostCny,
            BigDecimal virtualRemainingBalanceCny
    ) {
    }
}
