package com.lingshu.common.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderRequestTest {

    @Test
    void keepsAnImmutableSnapshotOfMessages() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                new ChatMessage("system", "Follow policy"),
                new ChatMessage("user", "Hello")
        ));

        ProviderRequest request = new ProviderRequest(
                "trace-1",
                "tenant-1",
                "logical-model",
                messages,
                0.5
        );
        messages.clear();

        assertEquals(2, request.messages().size());
        assertEquals("system", request.messages().getFirst().role());
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(new ChatMessage("user", "Another message")));
    }

    @Test
    void rejectsOutOfRangeTemperature() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderRequest(
                "trace-1",
                "tenant-1",
                "logical-model",
                List.of(new ChatMessage("user", "Hello")),
                2.1
        ));
    }
}
