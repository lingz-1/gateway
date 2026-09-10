package com.lingshu.core.api;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.provider.ModelProvider;
import com.lingshu.core.provider.ProviderHealth;
import com.lingshu.core.provider.ProviderUsageException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StreamingProviderFallbackIntegrationTest.ProviderConfiguration.class)
class StreamingProviderFallbackIntegrationTest {

    private static final String MODEL = "stream-fallback-model";

    @LocalServerPort
    private int port;

    @Test
    void fallsBackBeforeFirstDeltaAndAggregatesUsage() throws Exception {
        String body = "{\"model\":\"" + MODEL + "\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-Trace-Id", "trace-stream-fallback")
                .header("X-Tenant-Id", "tenant-stream-fallback")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("fallback response"));
        assertTrue(response.body().contains("\"provider\":\"b-success\""));
        assertTrue(response.body().contains("\"providerAttempts\":[\"a-failing\",\"b-success\"]"));
        assertTrue(response.body().contains("\"prompt_tokens\":5"));
        assertTrue(response.body().contains("\"completion_tokens\":5"));
        assertTrue(response.body().contains("[DONE]"));
    }

    @TestConfiguration
    static class ProviderConfiguration {

        @Bean
        ModelProvider failingStreamingProvider() {
            return new TestProvider("a-failing") {
                @Override
                public ProviderResponse invoke(ProviderRequest request) {
                    throw new ProviderUsageException("provider unavailable", 2, 1);
                }
            };
        }

        @Bean
        ModelProvider successfulStreamingProvider() {
            return new TestProvider("b-success") {
                @Override
                public ProviderResponse invoke(ProviderRequest request) {
                    return new ProviderResponse(id(), MODEL, "fallback response", 3, 4, "stop");
                }
            };
        }
    }

    private abstract static class TestProvider implements ModelProvider {

        private final String id;

        private TestProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> supportedModels() {
            return Set.of(MODEL);
        }

        @Override
        public ProviderHealth health() {
            return new ProviderHealth(id, ProviderHealth.Status.UP, Instant.now());
        }
    }
}
