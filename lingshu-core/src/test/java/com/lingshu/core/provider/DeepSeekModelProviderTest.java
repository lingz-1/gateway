package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekModelProviderTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>(
            "{\"choices\":[{\"message\":{\"content\":\"hello from deepseek\"}}],"
                    + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":4}}"
    );
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();
    private final AtomicInteger requestCount = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handleRequest);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleRequestAndParsesResponse() throws Exception {
        DeepSeekModelProvider provider = provider();

        ProviderResponse response = provider.invoke(
                new ProviderRequest("trace-1", "tenant-1", "deepseek-v4flash", "hello")
        );

        assertEquals("deepseek", response.provider());
        assertEquals("deepseek-v4flash", response.model());
        assertEquals("hello from deepseek", response.content());
        assertEquals(7, response.inputTokens());
        assertEquals(4, response.outputTokens());
        assertEquals("Bearer test-key", authorization.get());
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertEquals("deepseek-v4-flash", request.path("model").asText());
        assertEquals("hello", request.path("messages").get(0).path("content").asText());
        assertEquals("user", request.path("messages").get(0).path("role").asText());
        assertTrue(!request.path("stream").asBoolean());
    }

    @Test
    void rejectsProviderHttpErrorWithoutExposingBody() {
        responseStatus.set(401);
        responseBody.set("{\"error\":\"sensitive provider detail\","
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}");

        ProviderUsageException exception = assertThrows(
                ProviderUsageException.class,
                () -> provider().invoke(
                        new ProviderRequest("trace-1", "tenant-1", "deepseek-v4flash", "hello")
                )
        );

        assertEquals("DeepSeek provider returned HTTP 401", exception.getMessage());
        assertEquals(12, exception.inputTokens());
        assertEquals(3, exception.outputTokens());
    }

    @Test
    void rejectsResponseWithoutTextContent() {
        responseBody.set("{\"choices\":[{\"message\":{}}]}");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> provider().invoke(
                        new ProviderRequest("trace-1", "tenant-1", "deepseek-v4flash", "hello")
                )
        );

        assertTrue(exception.getMessage().contains("does not contain text content"));
    }

    @Test
    void requiresApiKey() {
        LingShuProperties properties = properties();
        properties.getProvider().getDeepseek().setApiKey("");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DeepSeekModelProvider(properties, objectMapper, HttpClient.newHttpClient())
        );

        assertTrue(exception.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    void retriesTransientFailureAndAccumulatesReportedUsage() {
        failuresBeforeSuccess.set(1);
        LingShuProperties properties = properties();
        properties.getProvider().getDeepseek().setMaxAttempts(2);
        properties.getProvider().getDeepseek().setRetryBackoff(java.time.Duration.ZERO);
        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties, objectMapper, HttpClient.newHttpClient());

        ProviderResponse response = provider.invoke(
                new ProviderRequest("trace-retry", "tenant-1", "deepseek-v4flash", "hello")
        );

        assertEquals(2, requestCount.get());
        assertEquals(9, response.inputTokens());
        assertEquals(5, response.outputTokens());
    }

    @Test
    void opensCircuitAfterConfiguredFailureThreshold() {
        responseStatus.set(500);
        LingShuProperties properties = properties();
        properties.getProvider().getDeepseek().setCircuitFailureThreshold(1);
        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties, objectMapper, HttpClient.newHttpClient());

        assertThrows(ProviderUsageException.class, () -> provider.invoke(
                new ProviderRequest("trace-circuit", "tenant-1", "deepseek-v4flash", "hello")));

        assertEquals(ProviderHealth.Status.DOWN, provider.health().status());
        assertThrows(ProviderRoutingException.class, () -> provider.invoke(
                new ProviderRequest("trace-circuit-2", "tenant-1", "deepseek-v4flash", "hello")));
    }

    private DeepSeekModelProvider provider() {
        return new DeepSeekModelProvider(properties(), objectMapper, HttpClient.newHttpClient());
    }

    private LingShuProperties properties() {
        LingShuProperties properties = new LingShuProperties();
        LingShuProperties.DeepSeek deepseek = properties.getProvider().getDeepseek();
        deepseek.setModel("deepseek-v4flash");
        deepseek.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        deepseek.setApiKey("test-key");
        return properties;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        int status = responseStatus.get();
        String response = responseBody.get();
        if (failuresBeforeSuccess.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            status = 500;
            response = "{\"error\":\"temporary\",\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}";
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
