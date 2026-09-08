package com.lingshu.core.api;

import com.lingshu.common.contracts.TraceHeaders;
import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatCompletionResponse;
import com.lingshu.core.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatCompletionController {

    private final ChatCompletionService service;
    private final StreamingChatCompletionService streamingService;

    public ChatCompletionController(
            ChatCompletionService service,
            StreamingChatCompletionService streamingService
    ) {
        this.service = service;
        this.streamingService = streamingService;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> complete(
            @Valid @RequestBody ChatCompletionRequest request,
            @RequestHeader(name = TraceHeaders.TENANT_ID, defaultValue = "default") String tenantId,
            HttpServletRequest servletRequest
    ) {
        String traceId = (String) servletRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (Boolean.TRUE.equals(request.stream())) {
            SseEmitter emitter = streamingService.stream(request, traceId, tenantId);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(emitter);
        }
        ChatCompletionResponse response = service.complete(request, traceId, tenantId);
        return ResponseEntity.ok()
                .header(TraceHeaders.PROVIDER, response.metadata().provider())
                .header(TraceHeaders.CACHE_STATUS, response.metadata().cacheStatus().name())
                .header(TraceHeaders.PROCESSING_TIME, Long.toString(response.metadata().totalDurationMs()))
                .body(response);
    }
}
