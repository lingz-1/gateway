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
                0.5,
                512,
                0.9
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
                2.1,
                null,
                null
        ));
    }

    @Test
    void rejectsInvalidSamplingParameters() {
        List<ChatMessage> messages = List.of(new ChatMessage("user", "Hello"));

        assertThrows(IllegalArgumentException.class, () -> new ProviderRequest(
                "trace-1", "tenant-1", "logical-model", messages, null, 0, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProviderRequest(
                "trace-1", "tenant-1", "logical-model", messages, null, null, 1.1
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProviderRequest(
                "trace-1", "tenant-1", "logical-model", messages,
                null, null, null, null, -2.1, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new ProviderRequest(
                "trace-1", "tenant-1", "logical-model", messages,
                null, null, null, null, null, 2.1
        ));
    }

    @Test
    void preservesExtendedSamplingParameters() {
        ProviderRequest request = new ProviderRequest(
                "trace-1",
                "tenant-1",
                "logical-model",
                List.of(new ChatMessage("user", "Hello")),
                0.5,
                512,
                0.9,
                42L,
                -0.4,
                0.8
        );

        assertEquals(42L, request.seed());
        assertEquals(-0.4, request.frequencyPenalty());
        assertEquals(0.8, request.presencePenalty());
    }
}
