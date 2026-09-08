package com.lingshu.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "lingshu.gateway.api-key.enabled=true",
                "lingshu.gateway.api-key.value=test-api-key"
        }
)
class GatewayFilterIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void rejectsMissingApiKeyAndPreservesTraceId() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/chat/completions")
                )
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", "gateway-trace")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
        assertEquals(1, response.headers().allValues("X-Trace-Id").size());
        assertEquals("gateway-trace", response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertTrue(response.body().contains("AUTHENTICATION_FAILED"));
        assertTrue(response.body().contains("gateway-trace"));
    }
}
