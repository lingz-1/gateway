package com.lingshu.core.processing;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ProviderResponse;
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
            processRange(context, 0, processors.size());
        } finally {
            context.closePolicyPermit();
        }

        return result(context, startedAt);
    }

    public ChatStreamingExecution prepareStreaming(
            ChatCompletionRequest request,
            String traceId,
            String tenantId
    ) {
        ChatProcessingContext context = new ChatProcessingContext(request, traceId, tenantId);
        long startedAt = System.nanoTime();
        int providerIndex = providerProcessorIndex();
        try {
            processRange(context, 0, providerIndex);
            if (!context.completed()) {
                return new ChatStreamingExecution(context, startedAt, providerIndex + 1, null);
            }
            processRange(context, providerIndex, processors.size());
        } catch (RuntimeException exception) {
            context.closePolicyPermit();
            throw exception;
        }
        context.closePolicyPermit();
        ChatProcessingResult result = result(context, startedAt);
        return new ChatStreamingExecution(context, startedAt, processors.size(), result);
    }

    public ChatProcessingResult finishStreaming(
            ChatStreamingExecution execution,
            ProviderResponse providerResponse
    ) {
        if (execution.completed()) {
            return execution.completedResult();
        }
        ChatProcessingContext context = execution.context();
        context.providerResponse(providerResponse);
        try {
            processRange(context, execution.nextProcessorIndex(), processors.size());
        } finally {
            context.closePolicyPermit();
        }
        return result(context, execution.startedAtNanos());
    }

    public void abortStreaming(ChatStreamingExecution execution) {
        execution.context().closePolicyPermit();
    }

    private void processRange(ChatProcessingContext context, int startInclusive, int endExclusive) {
        for (int index = startInclusive; index < endExclusive; index++) {
            ChatProcessor processor = processors.get(index);
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
    }

    private int providerProcessorIndex() {
        for (int index = 0; index < processors.size(); index++) {
            if (processors.get(index) instanceof ProviderInvokeProcessor) {
                return index;
            }
        }
        throw new IllegalStateException("Provider invoke processor is not configured");
    }

    private ChatProcessingResult result(ChatProcessingContext context, long startedAt) {
        long totalDurationMs = elapsedMs(startedAt);
        LOGGER.info(
                "Chat completion succeeded traceId={} tenantId={} provider={} providerAttempts={} model={} cacheStatus={} durationMs={} processors={}",
                context.traceId(),
                context.tenantId(),
                context.providerResponse().provider(),
                context.providerAttempts(),
                context.request().model(),
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
