package com.lingshu.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "lingshu.gateway.api-key.enabled=true",
                "lingshu.gateway.api-key.value=test-api-key",
                "lingshu.gateway.request-policy.max-body-bytes=32"
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

    @Test
    void rejectsMissingOrAmbiguousTenantIdAfterAuthentication() throws Exception {
        HttpResponse<String> missing = send(baseRequest("{}")
                .header("X-API-Key", "test-api-key")
                .build());
        HttpResponse<String> duplicate = send(baseRequest("{}")
                .header("X-API-Key", "test-api-key")
                .header("X-Tenant-Id", "tenant-a")
                .header("X-Tenant-Id", "tenant-b")
                .build());
        HttpResponse<String> invalid = send(baseRequest("{}")
                .header("X-API-Key", "test-api-key")
                .header("X-Tenant-Id", "tenant/a")
                .build());

        assertEquals(400, missing.statusCode());
        assertEquals(400, duplicate.statusCode());
        assertEquals(400, invalid.statusCode());
        assertTrue(missing.body().contains("INVALID_REQUEST"));
        assertTrue(duplicate.body().contains("INVALID_REQUEST"));
        assertTrue(invalid.body().contains("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnsupportedMediaType() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(chatUri())
                .header("X-API-Key", "test-api-key")
                .header("X-Tenant-Id", "tenant-a")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build();

        HttpResponse<String> response = send(request);

        assertEquals(415, response.statusCode());
        assertTrue(response.body().contains("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void rejectsOversizedKnownAndChunkedBodies() throws Exception {
        String oversized = "x".repeat(64);
        HttpResponse<String> knownLength = send(baseRequest(oversized)
                .header("X-API-Key", "test-api-key")
                .header("X-Tenant-Id", "tenant-a")
                .build());
        HttpRequest chunkedRequest = HttpRequest.newBuilder(chatUri())
                .header("X-API-Key", "test-api-key")
                .header("X-Tenant-Id", "tenant-a")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(
                        oversized.getBytes(StandardCharsets.UTF_8)
                )))
                .build();

        HttpResponse<String> chunked = send(chunkedRequest);

        assertEquals(413, knownLength.statusCode());
        assertEquals(413, chunked.statusCode());
        assertTrue(knownLength.body().contains("PAYLOAD_TOO_LARGE"));
        assertTrue(chunked.body().contains("PAYLOAD_TOO_LARGE"));
        assertEquals("no-store", knownLength.headers().firstValue("Cache-Control").orElseThrow());
    }

    private HttpRequest.Builder baseRequest(String body) {
        return HttpRequest.newBuilder(chatUri())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private URI chatUri() {
        return URI.create("http://localhost:" + port + "/v1/chat/completions");
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
