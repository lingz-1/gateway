package com.lingshu.core.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatCompletionIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void routesRequestsByModelAndReturnsProcessingMetadata() throws Exception {
        HttpResponse<String> standard = post("stub-echo-v1", "trace-standard");
        HttpResponse<String> fast = post("stub-fast-v1", "trace-fast");

        assertEquals(200, standard.statusCode());
        assertEquals(1, standard.headers().allValues("X-Trace-Id").size());
        assertEquals("stub", standard.headers().firstValue("X-LingShu-Provider").orElseThrow());
        assertTrue(standard.body().contains("\"provider\":\"stub\""));
        assertTrue(standard.body().contains("\"name\":\"trace\""));
        assertTrue(standard.body().contains("\"name\":\"router\""));
        assertTrue(standard.body().contains("\"name\":\"provider-invoke\""));

        assertEquals(200, fast.statusCode());
        assertEquals("stub-fast", fast.headers().firstValue("X-LingShu-Provider").orElseThrow());
        assertTrue(fast.body().contains("stub-fast:user:hello"));
    }

    @Test
    void returnsExactCacheHitAndSkipsProviderProcessors() throws Exception {
        HttpResponse<String> first = post(
                "stub-echo-v1",
                "trace-cache-first",
                "tenant-cache",
                "cache-probe"
        );
        HttpResponse<String> second = post(
                "stub-echo-v1",
                "trace-cache-second",
                "tenant-cache",
                "cache-probe"
        );

        assertEquals("MISS", first.headers().firstValue("X-LingShu-Cache").orElseThrow());
        assertTrue(first.body().contains("\"cacheStatus\":\"MISS\""));
        assertEquals("EXACT", second.headers().firstValue("X-LingShu-Cache").orElseThrow());
        assertTrue(second.body().contains("\"cacheStatus\":\"EXACT\""));
        assertTrue(second.body().contains("\"name\":\"exact-cache-lookup\""));
        assertFalse(second.body().contains("\"name\":\"router\""));
        assertFalse(second.body().contains("\"name\":\"provider-invoke\""));
    }

    @Test
    void isolatesExactCacheByTenant() throws Exception {
        HttpResponse<String> tenantA = post(
                "stub-fast-v1",
                "trace-tenant-a",
                "tenant-a",
                "tenant-isolation-probe"
        );
        HttpResponse<String> tenantB = post(
                "stub-fast-v1",
                "trace-tenant-b",
                "tenant-b",
                "tenant-isolation-probe"
        );

        assertEquals("MISS", tenantA.headers().firstValue("X-LingShu-Cache").orElseThrow());
        assertEquals("MISS", tenantB.headers().firstValue("X-LingShu-Cache").orElseThrow());
    }

    @Test
    void rejectsInvalidRequestsWithTraceableError() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", "trace-invalid")
                .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"stub-echo-v1\",\"messages\":[]}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertEquals("trace-invalid", response.headers().firstValue("X-Trace-Id").orElseThrow());
        assertTrue(response.body().contains("INVALID_REQUEST"));
        assertTrue(response.body().contains("trace-invalid"));
    }

    @Test
    void returnsProviderErrorForUnknownModel() throws Exception {
        HttpResponse<String> response = post("unknown-model", "trace-unknown");

        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("PROVIDER_UNAVAILABLE"));
        assertTrue(response.body().contains("trace-unknown"));
    }

    @Test
    void streamsOpenAiCompatibleChunksWithUsageFinalFrame() throws Exception {
        String body = "{\"model\":\"stub-echo-v1\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"stream me\"}]}";
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("X-Trace-Id", "trace-stream")
                .header("X-Tenant-Id", "tenant-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));
        assertTrue(response.body().contains("chat.completion.chunk"));
        assertTrue(response.body().contains("prompt_tokens"));
        assertTrue(response.body().contains("[DONE]"));
    }

    @Test
    void appliesTenantPiiPolicyUpdatesWithoutRestart() throws Exception {
        String tenantId = "tenant-dynamic-policy";
        assertEquals(200, putPolicy(tenantId, false).statusCode());

        HttpResponse<String> unredacted = post(
                "stub-echo-v1",
                "trace-pii-disabled",
                tenantId,
                "phone 13800138000"
        );

        assertEquals(200, unredacted.statusCode());
        assertTrue(unredacted.body().contains("13800138000"));
        assertFalse(unredacted.body().contains("[PHONE]"));

        assertEquals(200, putPolicy(tenantId, true).statusCode());

        HttpResponse<String> redacted = post(
                "stub-echo-v1",
                "trace-pii-enabled",
                tenantId,
                "phone 13900139000"
        );

        assertEquals(200, redacted.statusCode());
        assertFalse(redacted.body().contains("13900139000"));
        assertTrue(redacted.body().contains("[PHONE]"));
    }

    private HttpResponse<String> post(String model, String traceId) throws Exception {
        return post(model, traceId, "tenant-test", "hello");
    }

    private HttpResponse<String> post(
            String model,
            String traceId,
            String tenantId,
            String content
    ) throws Exception {
        String body = "{\"model\":\"" + model
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + content
                + "\"}]}";
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .header("X-Trace-Id", traceId)
                .header("X-Tenant-Id", tenantId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI endpoint() {
        return URI.create("http://localhost:" + port + "/v1/chat/completions");
    }

    private HttpResponse<String> putPolicy(String tenantId, boolean piiRedactionEnabled) throws Exception {
        String body = "{"
                + "\"enabled\":true,"
                + "\"allowedModels\":[\"stub-echo-v1\"],"
                + "\"piiRedactionEnabled\":" + piiRedactionEnabled + ","
                + "\"exactCacheEnabled\":false,"
                + "\"semanticCacheEnabled\":false,"
                + "\"requestsPerMinute\":0,"
                + "\"maxConcurrentRequests\":0,"
                + "\"inputPriceUsdPerMillion\":0.22,"
                + "\"outputPriceUsdPerMillion\":0.66"
                + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/internal/tenants/" + tenantId + "/policy"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
