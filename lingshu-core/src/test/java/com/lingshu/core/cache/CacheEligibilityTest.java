package com.lingshu.core.cache;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatFunctionCall;
import com.lingshu.common.dto.ChatFunctionDefinition;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.common.dto.ChatTool;
import com.lingshu.common.dto.ChatToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheEligibilityTest {

    @Test
    void allowsPlainTextAndRejectsEveryToolContext() {
        assertTrue(CacheEligibility.isCacheable(request(
                List.of(new ChatMessage("user", "hello")), null, null)));
        assertFalse(CacheEligibility.isCacheable(request(
                List.of(new ChatMessage("user", "hello")),
                List.of(new ChatTool("function", new ChatFunctionDefinition(
                        "lookup", null, Map.of("type", "object"), null))),
                "auto")));
        assertFalse(CacheEligibility.isCacheable(request(
                List.of(new ChatMessage("assistant", null, null, null, List.of(
                        new ChatToolCall("call-1", "function", new ChatFunctionCall("lookup", "{}"))
                ))), null, null)));
        assertFalse(CacheEligibility.isCacheable(request(
                List.of(new ChatMessage("tool", "result", null, "call-1", null)), null, null)));
    }

    @Test
    void allowsExactButRejectsSemanticCachingForStructuredContent() {
        ChatCompletionRequest request = request(
                List.of(new ChatMessage(
                        "user",
                        List.of(
                                Map.of("type", "text", "text", "describe"),
                                Map.of("type", "image_url", "image_url", Map.of(
                                        "url", "https://example.com/image.png"
                                ))
                        ),
                        null,
                        null,
                        null
                )),
                null,
                null
        );

        assertTrue(CacheEligibility.isCacheable(request));
        assertFalse(CacheEligibility.isSemanticCacheable(request));
    }

    private ChatCompletionRequest request(
            List<ChatMessage> messages,
            List<ChatTool> tools,
            Object toolChoice
    ) {
        return new ChatCompletionRequest(
                "stub-echo-v1", messages, false, null, null, null,
                null, null, null, tools, toolChoice
        );
    }
}
