package com.lingshu.core.processing;

import com.lingshu.common.dto.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ChatProcessorEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatProcessorEngine.class);

    private final List<ChatProcessor> processors;

    public ChatProcessorEngine(List<ChatProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    public ChatProcessingResult execute(ChatCompletionRequest request, String traceId, String tenantId) {
        ChatProcessingContext context = new ChatProcessingContext(request, traceId, tenantId);
        long startedAt = System.nanoTime();

        try {
            for (ChatProcessor processor : processors) {
                if (!processor.shouldProcess(context)) {
                    continue;
                }
                long processorStartedAt = System.nanoTime();
                try {
                    processor.process(context);
                } finally {
                    context.addStep(processor.name(), elapsedMs(processorStartedAt));
                }
            }
        } finally {
            context.closePolicyPermit();
        }

        long totalDurationMs = elapsedMs(startedAt);
        LOGGER.info(
                "Chat completion succeeded traceId={} tenantId={} provider={} model={} cacheStatus={} durationMs={} processors={}",
                traceId,
                tenantId,
                context.providerResponse().provider(),
                request.model(),
                context.cacheStatus(),
                totalDurationMs,
                context.steps().stream().map(step -> step.name()).toList()
        );
        return new ChatProcessingResult(context, totalDurationMs);
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
