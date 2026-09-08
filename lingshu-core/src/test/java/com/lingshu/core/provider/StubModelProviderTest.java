package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StubModelProviderTest {

    @Test
    void returnsDeterministicResponseWithoutExternalApi() {
        StubModelProvider provider = new StubModelProvider(new LingShuProperties());
        ProviderRequest request = new ProviderRequest("trace-1", "tenant-1", "stub-echo-v1", "hello");

        ProviderResponse response = provider.invoke(request);

        assertEquals("stub", response.provider());
        assertEquals("stub-echo-v1", response.model());
        assertEquals("stub:hello", response.content());
        assertEquals(2, response.inputTokens());
        assertEquals(ProviderHealth.Status.UP, provider.health().status());
    }
}
