package com.lingshu.core.api;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatCompletionResponse;
import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.processing.ChatProcessingContext;
import com.lingshu.core.processing.ChatProcessingResult;
import com.lingshu.core.processing.ChatProcessorEngine;
import com.lingshu.core.processing.ChatStreamingExecution;
import com.lingshu.core.provider.ModelProvider;
import com.lingshu.core.provider.ModelProviderRouter;
import com.lingshu.core.provider.ProviderRoutingException;
import com.lingshu.core.provider.ProviderStreamCancelledException;
import com.lingshu.core.provider.ProviderStreamConsumer;
import com.lingshu.core.provider.ProviderUsageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class StreamingChatCompletionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingChatCompletionService.class);

    private final ChatCompletionService completionService;
    private final ChatProcessorEngine engine;
    private final ModelProviderRouter router;
    private final ExecutorService streamingExecutor;

    public StreamingChatCompletionService(
            ChatCompletionService completionService,
            ChatProcessorEngine engine,
            ModelProviderRouter router,
            ExecutorService streamingExecutor
    ) {
        this.completionService = completionService;
        this.engine = engine;
        this.router = router;
        this.streamingExecutor = streamingExecutor;
    }

    public SseEmitter stream(ChatCompletionRequest request, String traceId, String tenantId) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Runnable> cancelUpstream = new AtomicReference<>();
        emitter.onTimeout(() -> cancel(cancelled, cancelUpstream));
        emitter.onError(ignored -> cancel(cancelled, cancelUpstream));
        emitter.onCompletion(() -> cancel(cancelled, cancelUpstream));
        streamingExecutor.submit(() -> emit(
                request, traceId, tenantId, emitter, cancelled, cancelUpstream));
        return emitter;
    }

    private void emit(
            ChatCompletionRequest request,
            String traceId,
            String tenantId,
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AtomicReference<Runnable> cancelUpstream
    ) {
        long startedAt = System.nanoTime();
        String responseId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        ChatStreamingExecution execution = null;
        boolean processingCompleted = false;
        try {
            execution = engine.prepareStreaming(request, traceId, tenantId);
            ChatProcessingResult result;
            if (execution.completed()) {
                result = execution.completedResult();
                ProviderResponse cachedResponse = result.context().providerResponse();
                sendDelta(emitter, cancelled, responseId, created, cachedResponse.model(),
                        cachedResponse.content());
            } else {
                ProviderResponse providerResponse = invokeProviderStream(
                        execution.context(), emitter, cancelled, cancelUpstream, responseId, created);
                result = engine.finishStreaming(execution, providerResponse);
            }
            ChatCompletionResponse response = completionService.responseFrom(
                    result, traceId, tenantId, responseId, created);
            processingCompleted = true;
            sendFinal(emitter, response);
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (ProviderStreamCancelledException exception) {
            if (execution != null) {
                engine.abortStreaming(execution);
            }
            if (!processingCompleted) {
                completionService.recordFailure(exception, traceId, tenantId, startedAt);
            }
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (RuntimeException exception) {
            if (execution != null) {
                engine.abortStreaming(execution);
            }
            if (!processingCompleted) {
                completionService.recordFailure(exception, traceId, tenantId, startedAt);
            }
            try {
                if (!cancelled.get()) {
                    emitter.send(SseEmitter.event().name("error").data(Map.of(
                            "code", "STREAMING_ERROR",
                            "message", "Streaming completion failed",
                            "traceId", traceId
                    )));
                }
            } catch (IOException ignored) {
                // The client connection is already unavailable.
            }
            emitter.completeWithError(exception);
        }
    }

    private ProviderResponse invokeProviderStream(
            ChatProcessingContext context,
            SseEmitter emitter,
            AtomicBoolean cancelled,
            AtomicReference<Runnable> cancelUpstream,
            String responseId,
            long created
    ) {
        ProviderRequest providerRequest = new ProviderRequest(
                context.traceId(),
                context.tenantId(),
                context.request().model(),
                context.request().messages(),
                context.request().temperature(),
                context.request().max_tokens(),
                context.request().top_p()
        );
        List<ModelProvider> candidates = context.providerCandidates().isEmpty()
                ? List.of(context.provider())
                : context.providerCandidates();
        int failedInputTokens = 0;
        int failedOutputTokens = 0;
        RuntimeException lastFailure = null;
        for (ModelProvider candidate : candidates) {
            context.provider(candidate);
            context.addProviderAttempt(candidate.id());
            long startedAt = router.beginAttempt(candidate);
            AtomicBoolean emitted = new AtomicBoolean();
            try {
                ProviderResponse response = candidate.stream(providerRequest, new ProviderStreamConsumer() {
                    @Override
                    public void onDelta(String delta) throws IOException {
                        if (delta == null || delta.isEmpty()) {
                            return;
                        }
                        sendDelta(emitter, cancelled, responseId, created,
                                context.request().model(), delta);
                        emitted.set(true);
                    }

                    @Override
                    public void onOpen(Runnable cancellation) {
                        cancelUpstream.set(cancellation);
                        if (cancelled.get()) {
                            cancel(cancelled, cancelUpstream);
                        }
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }
                });
                if (cancelled.get()) {
                    throw cancelledException(null);
                }
                router.recordSuccess(candidate, startedAt);
                return withFailedUsage(response, failedInputTokens, failedOutputTokens);
            } catch (ProviderStreamCancelledException exception) {
                router.recordCancellation(candidate);
                throw exception;
            } catch (ProviderUsageException exception) {
                router.recordFailure(candidate, startedAt);
                failedInputTokens += exception.inputTokens();
                failedOutputTokens += exception.outputTokens();
                logProviderFailure(context, candidate, emitted.get(), exception);
                if (emitted.get()) {
                    throw exception;
                }
                lastFailure = exception;
            } catch (ProviderRoutingException exception) {
                router.recordFailure(candidate, startedAt);
                failedInputTokens += exception.inputTokens();
                failedOutputTokens += exception.outputTokens();
                logProviderFailure(context, candidate, emitted.get(), exception);
                if (emitted.get()) {
                    throw exception;
                }
                lastFailure = exception;
            } catch (IllegalStateException exception) {
                router.recordFailure(candidate, startedAt);
                logProviderFailure(context, candidate, emitted.get(), exception);
                if (emitted.get()) {
                    throw exception;
                }
                lastFailure = exception;
            } finally {
                cancelUpstream.set(null);
            }
        }
        throw new ProviderRoutingException(
                "All providers failed for model: " + context.request().model(),
                lastFailure,
                failedInputTokens,
                failedOutputTokens
        );
    }

    private void logProviderFailure(
            ChatProcessingContext context,
            ModelProvider provider,
            boolean emitted,
            RuntimeException exception
    ) {
        LOGGER.warn(
                "Streaming provider attempt failed traceId={} tenantId={} model={} provider={} emitted={} failure={}",
                context.traceId(),
                context.tenantId(),
                context.request().model(),
                provider.id(),
                emitted,
                exception.getClass().getSimpleName()
        );
    }

    private void sendDelta(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            String responseId,
            long created,
            String model,
            String delta
    ) throws IOException {
        if (cancelled.get()) {
            throw cancelledException(null);
        }
        try {
            emitter.send(SseEmitter.event().data(Map.of(
                    "id", responseId,
                    "object", "chat.completion.chunk",
                    "created", created,
                    "model", model,
                    "choices", List.of(Map.of(
                            "index", 0,
                            "delta", Map.of("content", delta),
                            "finish_reason", ""
                    ))
            )));
        } catch (IOException exception) {
            cancelled.set(true);
            throw cancelledException(exception);
        }
    }

    private void sendFinal(SseEmitter emitter, ChatCompletionResponse response) throws IOException {
        emitter.send(SseEmitter.event().data(Map.of(
                "id", response.id(),
                "object", "chat.completion.chunk",
                "created", response.created(),
                "model", response.model(),
                "choices", List.of(Map.of(
                        "index", 0,
                        "delta", Map.of(),
                        "finish_reason", response.choices().getFirst().finish_reason()
                )),
                "usage", response.usage(),
                "metadata", response.metadata()
        )));
    }

    private ProviderResponse withFailedUsage(
            ProviderResponse response,
            int failedInputTokens,
            int failedOutputTokens
    ) {
        if (failedInputTokens == 0 && failedOutputTokens == 0) {
            return response;
        }
        return new ProviderResponse(
                response.provider(),
                response.model(),
                response.content(),
                response.inputTokens() + failedInputTokens,
                response.outputTokens() + failedOutputTokens,
                response.finishReason()
        );
    }

    private ProviderStreamCancelledException cancelledException(IOException cause) {
        IOException cancellation = cause == null
                ? new IOException("Streaming client disconnected")
                : cause;
        return new ProviderStreamCancelledException("Streaming client disconnected", cancellation);
    }

    private void cancel(
            AtomicBoolean cancelled,
            AtomicReference<Runnable> cancelUpstream
    ) {
        cancelled.set(true);
        Runnable cancellation = cancelUpstream.getAndSet(null);
        if (cancellation != null) {
            cancellation.run();
        }
    }
}
