package com.lingshu.common.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    @Test
    void acceptsAndSummarizesMultimodalUserContent() {
        ChatMessage message = new ChatMessage(
                "user",
                List.of(
                        Map.of("type", "text", "text", "Describe this"),
                        Map.of("type", "image_url", "image_url", Map.of(
                                "url", "data:image/png;base64,AAAA",
                                "detail", "low"
                        )),
                        Map.of("type", "input_audio", "input_audio", Map.of(
                                "data", "AAAA",
                                "format", "wav"
                        )),
                        Map.of("type", "file", "file", Map.of("file_id", "file-1"))
                ),
                null,
                null,
                null
        );

        assertTrue(message.hasStructuredContent());
        assertEquals("user:Describe this[image][audio][file]", message.promptText());
    }

    @Test
    void rejectsInvalidOrRoleIncompatibleContentParts() {
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(
                "user",
                List.of(Map.of("type", "image_url", "image_url", Map.of(
                        "url", "https://example.com/image.png",
                        "detail", "maximum"
                ))),
                null,
                null,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(
                "system",
                List.of(Map.of("type", "image_url", "image_url", Map.of(
                        "url", "https://example.com/image.png"
                ))),
                null,
                null,
                null
        ));
    }
}
