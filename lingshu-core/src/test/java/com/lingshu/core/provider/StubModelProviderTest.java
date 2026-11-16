package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StubModelProviderTest {

    @Test
    void returnsDeterministicResponseWithoutExternalApi() {
        StubModelProvider provider = new StubModelProvider(new LingShuProperties());
        ProviderRequest request = new ProviderRequest(
                "trace-1",
                "tenant-1",
                "stub-echo-v1",
                List.of(new ChatMessage("user", "hello")),
                0.7,
                null,
                null
        );

        ProviderResponse response = provider.invoke(request);

        assertEquals("stub", response.provider());
        assertEquals("stub-echo-v1", response.model());
        assertEquals("stub:user:hello", response.content());
        assertEquals(3, response.inputTokens());
        assertEquals(ProviderHealth.Status.UP, provider.health().status());
    }

    @Test
    void streamsInConfigurableChunksAndObservesCancellation() {
        LingShuProperties properties = new LingShuProperties();
        properties.getProvider().getStub().setStreamChunks(3);
        StubModelProvider provider = new StubModelProvider(properties);
        ProviderRequest request = new ProviderRequest(
                "trace-stream",
                "tenant-1",
                "stub-echo-v1",
                List.of(new ChatMessage("user", "hello")),
                0.7,
                null,
                null
        );
        List<String> deltas = new ArrayList<>();

        provider.stream(request, deltas::add);

        assertEquals("stub:user:hello", String.join("", deltas));
        assertThrows(ProviderStreamCancelledException.class, () -> provider.stream(
                request,
                new ProviderStreamConsumer() {
                    @Override
                    public void onDelta(String content) {
                    }

                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                }
        ));
    }

    @Test
    void injectsFailureBeforeTheFirstChunk() {
        LingShuProperties properties = new LingShuProperties();
        properties.getProvider().getStub().setStreamFailureMode("before-first");
        StubModelProvider provider = new StubModelProvider(properties);
        List<String> deltas = new ArrayList<>();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> provider.stream(request("trace-before"), deltas::add));

        assertEquals(0, deltas.size());
        assertEquals("Injected Stub failure before first stream chunk", exception.getMessage());
    }

    @Test
    void injectsTruncationAfterConfiguredChunks() {
        LingShuProperties properties = new LingShuProperties();
        properties.getProvider().getStub().setStreamChunks(5);
        properties.getProvider().getStub().setStreamFailureMode("truncate-after-chunks");
        properties.getProvider().getStub().setStreamFailureAfterChunks(2);
        StubModelProvider provider = new StubModelProvider(properties);
        List<String> deltas = new ArrayList<>();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> provider.stream(request("trace-truncated"), deltas::add));

        assertInstanceOf(ProviderStreamTruncatedException.class, exception);
        assertEquals(2, deltas.size());
    }

    private ProviderRequest request(String traceId) {
        return new ProviderRequest(
                traceId,
                "tenant-1",
                "stub-echo-v1",
                List.of(new ChatMessage("user", "hello")),
                0.7,
                null,
                null
        );
    }
}
