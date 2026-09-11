package com.lingshu.core.provider;

import com.lingshu.common.dto.ChatMessage;
import com.lingshu.common.dto.ChatFunctionCall;
import com.lingshu.common.dto.ChatFunctionDefinition;
import com.lingshu.common.dto.ChatTool;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekModelProviderTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>(
            "{\"choices\":[{\"message\":{\"content\":\"hello from deepseek\"},\"finish_reason\":\"length\"}],"
                    + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":4}}"
    );
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicBoolean delayedStream = new AtomicBoolean();
    private final AtomicReference<CountDownLatch> releaseStreamTail = new AtomicReference<>();
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

        ProviderResponse response = provider.invoke(new ProviderRequest(
                "trace-1",
                "tenant-1",
                "deepseek-v4flash",
                List.of(
                        new ChatMessage("system", "Answer concisely"),
                        new ChatMessage("user", "hello")
                ),
                0.4,
                512,
                0.9,
                42L,
                -0.4,
                0.8
        ));

        assertEquals("deepseek", response.provider());
        assertEquals("deepseek-v4flash", response.model());
        assertEquals("hello from deepseek", response.content());
        assertEquals(7, response.inputTokens());
        assertEquals(4, response.outputTokens());
        assertEquals("length", response.finishReason());
        assertEquals("Bearer test-key", authorization.get());
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertEquals("deepseek-v4-flash", request.path("model").asText());
        assertEquals(2, request.path("messages").size());
        assertEquals("Answer concisely", request.path("messages").get(0).path("content").asText());
        assertEquals("system", request.path("messages").get(0).path("role").asText());
        assertEquals("hello", request.path("messages").get(1).path("content").asText());
        assertEquals("user", request.path("messages").get(1).path("role").asText());
        assertEquals(0.4, request.path("temperature").asDouble());
        assertEquals(512, request.path("max_tokens").asInt());
        assertEquals(0.9, request.path("top_p").asDouble());
        assertEquals(42L, request.path("seed").asLong());
        assertEquals(-0.4, request.path("frequency_penalty").asDouble());
        assertEquals(0.8, request.path("presence_penalty").asDouble());
        assertTrue(!request.path("stream").asBoolean());
    }

    @Test
    void forwardsStreamingDeltaBeforeProviderResponseCompletes() throws Exception {
        delayedStream.set(true);
        CountDownLatch releaseTail = new CountDownLatch(1);
        releaseStreamTail.set(releaseTail);
        CountDownLatch firstDeltaReceived = new CountDownLatch(1);
        List<String> deltas = new CopyOnWriteArrayList<>();

        CompletableFuture<ProviderResponse> responseFuture = CompletableFuture.supplyAsync(() ->
                provider().stream(request("trace-stream"), delta -> {
                    deltas.add(delta);
                    firstDeltaReceived.countDown();
                }));

        try {
            assertTrue(firstDeltaReceived.await(2, TimeUnit.SECONDS));
            assertTrue(!responseFuture.isDone());
        } finally {
            releaseTail.countDown();
        }
        ProviderResponse response = responseFuture.get(2, TimeUnit.SECONDS);

        assertEquals(List.of("hello ", "from deepseek"), deltas);
        assertEquals("hello from deepseek", response.content());
        assertEquals(7, response.inputTokens());
        assertEquals(4, response.outputTokens());
        assertEquals("length", response.finishReason());
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertTrue(request.path("stream").asBoolean());
        assertTrue(request.path("stream_options").path("include_usage").asBoolean());
    }

    @Test
    void forwardsToolsAndParsesToolCallResponse() throws Exception {
        responseBody.set("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{"
                + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
        ProviderRequest toolRequest = new ProviderRequest(
                "trace-tools",
                "tenant-1",
                "deepseek-v4flash",
                List.of(new ChatMessage("user", "weather?")),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new ChatTool(
                        "function",
                        new ChatFunctionDefinition(
                                "get_weather",
                                "Get weather",
                                Map.of("type", "object"),
                                true
                        )
                )),
                "auto"
        );

        ProviderResponse response = provider().invoke(toolRequest);

        assertEquals(null, response.content());
        assertEquals("tool_calls", response.finishReason());
        assertEquals("call-1", response.toolCalls().getFirst().id());
        assertEquals("get_weather", response.toolCalls().getFirst().function().name());
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertEquals("get_weather", request.path("tools").get(0).path("function").path("name").asText());
        assertEquals("auto", request.path("tool_choice").asText());
    }

    @Test
    void streamsAndAssemblesToolCallDeltas() {
        responseBody.set("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"get_weather\",\"arguments\":\"{\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"arguments\":\"\\\"city\\\":\\\"Beijing\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n"
                + "data: [DONE]\n\n");
        List<String> fragments = new CopyOnWriteArrayList<>();

        ProviderResponse response = provider().stream(request("trace-stream-tools"), new ProviderStreamConsumer() {
            @Override
            public void onDelta(String content) {
            }

            @Override
            public void onToolCallDelta(
                    int index,
                    String id,
                    String type,
                    String name,
                    String arguments
            ) {
                if (arguments != null) {
                    fragments.add(arguments);
                }
            }
        });

        assertEquals(List.of("{", "\"city\":\"Beijing\"}"), fragments);
        assertEquals("{\"city\":\"Beijing\"}",
                response.toolCalls().getFirst().function().arguments());
        assertEquals("tool_calls", response.finishReason());
        assertEquals(null, response.content());
    }

    @Test
    void estimatesStreamingUsageWhenProviderOmitsUsage() {
        responseBody.set("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},"
                + "\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n");

        ProviderResponse response = provider().stream(request("trace-estimated-usage"), delta -> {
        });

        assertEquals("hello", response.content());
        assertEquals(3, response.inputTokens());
        assertEquals(2, response.outputTokens());
    }

    @Test
    void rejectsStreamingContentBeforeEmittingBeyondConfiguredLimit() {
        responseBody.set("data: {\"choices\":[{\"delta\":{\"content\":\"too long\"}}]}\n\n"
                + "data: [DONE]\n\n");
        LingShuProperties properties = properties();
        properties.getProvider().getDeepseek().setMaxStreamResponseChars(4);
        DeepSeekModelProvider provider = new DeepSeekModelProvider(
                properties,
                objectMapper,
                HttpClient.newHttpClient()
        );
        List<String> deltas = new CopyOnWriteArrayList<>();

        ProviderUsageException exception = assertThrows(
                ProviderUsageException.class,
                () -> provider.stream(request("trace-response-limit"), deltas::add)
        );

        assertTrue(exception.getMessage().contains("exceeded 4 characters"));
        assertEquals(3, exception.inputTokens());
        assertEquals(0, exception.outputTokens());
        assertTrue(deltas.isEmpty());
    }

    @Test
    void stopsReadingProviderStreamWhenConsumerDisconnects() {
        responseBody.set("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"second\"}}]}\n\n"
                + "data: [DONE]\n\n");

        assertThrows(ProviderStreamCancelledException.class, () ->
                provider().stream(request("trace-cancel"), delta -> {
                    throw new IOException("client disconnected");
                }));
    }

    @Test
    void rejectsProviderHttpErrorWithoutExposingBody() {
        responseStatus.set(401);
        responseBody.set("{\"error\":\"sensitive provider detail\","
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}");

        ProviderUsageException exception = assertThrows(
                ProviderUsageException.class,
                () -> provider().invoke(
                        request("trace-1")
                )
        );

        assertEquals("deepseek provider returned HTTP 401", exception.getMessage());
        assertEquals(12, exception.inputTokens());
        assertEquals(3, exception.outputTokens());
    }

    @Test
    void rejectsResponseWithoutTextContent() {
        responseBody.set("{\"choices\":[{\"message\":{}}]}");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> provider().invoke(
                        request("trace-1")
                )
        );

        assertTrue(exception.getMessage().contains("does not contain content or tool calls"));
    }

    @Test
    void requiresApiKey() {
        LingShuProperties properties = properties();
        properties.getProvider().getDeepseek().setApiKey("");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DeepSeekModelProvider(properties, objectMapper, HttpClient.newHttpClient())
        );

        assertTrue(exception.getMessage().contains("API key"));
    }

    @Test
    void retriesTransientFailureAndAccumulatesReportedUsage() {
        failuresBeforeSuccess.set(1);
        LingShuProperties properties = properties();
        properties.getProvider().getDeepseek().setMaxAttempts(2);
        properties.getProvider().getDeepseek().setRetryBackoff(java.time.Duration.ZERO);
        DeepSeekModelProvider provider = new DeepSeekModelProvider(properties, objectMapper, HttpClient.newHttpClient());

        ProviderResponse response = provider.invoke(
                request("trace-retry")
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
                request("trace-circuit")));

        assertEquals(ProviderHealth.Status.DOWN, provider.health().status());
        assertThrows(ProviderRoutingException.class, () -> provider.invoke(
                request("trace-circuit-2")));
    }

    private DeepSeekModelProvider provider() {
        return new DeepSeekModelProvider(properties(), objectMapper, HttpClient.newHttpClient());
    }

    private ProviderRequest request(String traceId) {
        return new ProviderRequest(
                traceId,
                "tenant-1",
                "deepseek-v4flash",
                List.of(new ChatMessage("user", "hello")),
                0.7,
                null,
                null
        );
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
        if (delayedStream.get()) {
            writeDelayedStream(exchange, status);
            return;
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void writeDelayedStream(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(status, 0);
        String first = "data: {\"choices\":[{\"delta\":{\"content\":\"hello \"},"
                + "\"finish_reason\":null}]}\n\n";
        exchange.getResponseBody().write(first.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        try {
            releaseStreamTail.get().await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while delaying stream", exception);
        }
        String tail = "data: {\"choices\":[{\"delta\":{\"content\":\"from deepseek\"},"
                + "\"finish_reason\":\"length\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,"
                + "\"completion_tokens\":4}}\n\n"
                + "data: [DONE]\n\n";
        exchange.getResponseBody().write(tail.getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
