package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StubModelProviderTest {

    @Test
    void returnsDeterministicResponseWithoutExternalApi() {
        StubModelProvider provider = new StubModelProvider(new LingShuProperties());
        ProviderRequest request = new ProviderRequest(
                "trace-1",
                "tenant-1",
                "stub-echo-v1",
                List.of(new ChatMessage("user", "hello")),
                0.7
        );

        ProviderResponse response = provider.invoke(request);

        assertEquals("stub", response.provider());
        assertEquals("stub-echo-v1", response.model());
        assertEquals("stub:user:hello", response.content());
        assertEquals(3, response.inputTokens());
        assertEquals(ProviderHealth.Status.UP, provider.health().status());
    }
}
