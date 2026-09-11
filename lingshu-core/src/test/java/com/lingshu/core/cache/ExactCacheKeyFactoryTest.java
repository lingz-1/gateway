package com.lingshu.core.cache;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactCacheKeyFactoryTest {

    private final ExactCacheKeyFactory factory = new ExactCacheKeyFactory(new LingShuProperties());

    @Test
    void createsStableSha256KeyAndIsolatesTenantAndParameters() {
        ChatCompletionRequest request = request("stub-echo-v1", 0.5, "hello");

        String key = factory.create("tenant-a", request);

        assertEquals(key, factory.create("tenant-a", request("stub-echo-v1", 0.5, "hello")));
        assertNotEquals(key, factory.create("tenant-b", request));
        assertNotEquals(key, factory.create("tenant-a", request("stub-fast-v1", 0.5, "hello")));
        assertNotEquals(key, factory.create("tenant-a", request("stub-echo-v1", 0.7, "hello")));
        assertNotEquals(key, factory.create("tenant-a", request("stub-echo-v1", 0.5, 256, null, "hello")));
        assertNotEquals(key, factory.create("tenant-a", request("stub-echo-v1", 0.5, null, 0.9, "hello")));
        assertNotEquals(key, factory.create("tenant-a", request(
                "stub-echo-v1", 0.5, null, null, 42L, null, null, "hello")));
        assertNotEquals(key, factory.create("tenant-a", request(
                "stub-echo-v1", 0.5, null, null, null, 0.4, null, "hello")));
        assertNotEquals(key, factory.create("tenant-a", request(
                "stub-echo-v1", 0.5, null, null, null, null, 0.4, "hello")));
        assertNotEquals(key, factory.create("tenant-a", request("stub-echo-v1", 0.5, "hello ")));
        assertTrue(key.matches("[0-9a-f]{64}"));
    }

    @Test
    void canonicalizesStructuredContentAndIsolatesMediaPayloads() {
        Map<String, Object> firstImage = new LinkedHashMap<>();
        firstImage.put("url", "data:image/png;base64,AAAA");
        firstImage.put("detail", "low");
        Map<String, Object> reorderedImage = new LinkedHashMap<>();
        reorderedImage.put("detail", "low");
        reorderedImage.put("url", "data:image/png;base64,AAAA");

        String key = factory.create("tenant-a", structuredRequest(firstImage));

        assertEquals(key, factory.create("tenant-a", structuredRequest(reorderedImage)));
        assertNotEquals(key, factory.create("tenant-a", structuredRequest(Map.of(
                "url", "data:image/png;base64,BBBB",
                "detail", "low"
        ))));
    }

    private ChatCompletionRequest request(String model, Double temperature, String content) {
        return request(model, temperature, null, null, content);
    }

    private ChatCompletionRequest request(
            String model,
            Double temperature,
            Integer maxTokens,
            Double topP,
            String content
    ) {
        return request(model, temperature, maxTokens, topP, null, null, null, content);
    }

    private ChatCompletionRequest request(
            String model,
            Double temperature,
            Integer maxTokens,
            Double topP,
            Long seed,
            Double frequencyPenalty,
            Double presencePenalty,
            String content
    ) {
        return new ChatCompletionRequest(
                model,
                List.of(new ChatMessage("user", content)),
                false,
                temperature,
                maxTokens,
                topP,
                seed,
                frequencyPenalty,
                presencePenalty
        );
    }

    private ChatCompletionRequest structuredRequest(Map<String, Object> image) {
        return new ChatCompletionRequest(
                "stub-echo-v1",
                List.of(new ChatMessage(
                        "user",
                        List.of(
                                Map.of("type", "text", "text", "Describe this"),
                                Map.of("type", "image_url", "image_url", image)
                        ),
                        null,
                        null,
                        null
                )),
                false,
                null,
                null,
                null
        );
    }
}
