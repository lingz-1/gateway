package com.lingshu.core.api;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatCompletionResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Service
public class StreamingChatCompletionService {

    private static final int CHUNK_CODE_POINTS = 16;

    private final ChatCompletionService completionService;
    private final ExecutorService streamingExecutor;

    public StreamingChatCompletionService(
            ChatCompletionService completionService,
            ExecutorService streamingExecutor
    ) {
        this.completionService = completionService;
        this.streamingExecutor = streamingExecutor;
    }

    public SseEmitter stream(ChatCompletionRequest request, String traceId, String tenantId) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
        streamingExecutor.submit(() -> emit(request, traceId, tenantId, emitter));
        return emitter;
    }

    private void emit(ChatCompletionRequest request, String traceId, String tenantId, SseEmitter emitter) {
        try {
            ChatCompletionResponse response = completionService.complete(request, traceId, tenantId);
            String content = response.choices().getFirst().message().content();
            int[] codePoints = content.codePoints().toArray();
            for (int offset = 0; offset < codePoints.length; offset += CHUNK_CODE_POINTS) {
                int length = Math.min(CHUNK_CODE_POINTS, codePoints.length - offset);
                String chunk = new String(codePoints, offset, length);
                emitter.send(SseEmitter.event().data(Map.of(
                        "id", response.id(),
                        "object", "chat.completion.chunk",
                        "created", response.created(),
                        "model", response.model(),
                        "choices", List.of(Map.of(
                                "index", 0,
                                "delta", Map.of("content", chunk),
                                "finish_reason", ""
                        ))
                )));
            }
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
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (RuntimeException exception) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "code", "STREAMING_ERROR",
                        "message", "Streaming completion failed",
                        "traceId", traceId
                )));
            } catch (IOException ignored) {
                // The client connection is already unavailable.
            }
            emitter.completeWithError(exception);
        }
    }
}
