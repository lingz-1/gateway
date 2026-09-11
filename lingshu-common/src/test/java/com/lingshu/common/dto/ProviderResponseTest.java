package com.lingshu.common.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderResponseTest {

    @Test
    void preservesProviderFinishReason() {
        ProviderResponse response = new ProviderResponse(
                "deepseek",
                "deepseek-v4flash",
                "partial answer",
                4,
                8,
                "length"
        );

        assertEquals("length", response.finishReason());
    }

    @Test
    void defaultsMissingFinishReasonToStop() {
        ProviderResponse response = new ProviderResponse(
                "stub",
                "stub-echo-v1",
                "answer",
                1,
                1,
                null
        );

        assertEquals("stop", response.finishReason());
    }

    @Test
    void acceptsToolCallWithoutTextContent() {
        ProviderResponse response = new ProviderResponse(
                "openai",
                "logical-model",
                null,
                10,
                5,
                "tool_calls",
                List.of(new ChatToolCall(
                        "call-1",
                        "function",
                        new ChatFunctionCall("get_weather", "{\"city\":\"Beijing\"}")
                ))
        );

        assertEquals("tool_calls", response.finishReason());
        assertEquals("get_weather", response.toolCalls().getFirst().function().name());
    }
}
