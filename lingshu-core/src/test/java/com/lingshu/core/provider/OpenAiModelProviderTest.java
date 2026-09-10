package com.lingshu.core.provider;

import com.lingshu.common.dto.ChatMessage;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiModelProviderTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handleRequest);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void invokesOpenAiChatCompletionsWithPreservedMessages() throws Exception {
        ProviderResponse response = provider().invoke(request());

        assertEquals("openai", response.provider());
        assertEquals("shared-chat-model", response.model());
        assertEquals("hello from openai", response.content());
        assertEquals(6, response.inputTokens());
        assertEquals(3, response.outputTokens());
        assertEquals("Bearer test-key", authorization.get());
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertEquals("gpt-4.1-mini", sent.path("model").asText());
        assertEquals("system", sent.path("messages").get(0).path("role").asText());
        assertEquals("user", sent.path("messages").get(1).path("role").asText());
        assertTrue(!sent.path("stream").asBoolean());
    }

    @Test
    void streamsOpenAiDeltasAndFinalUsage() throws Exception {
        List<String> deltas = new ArrayList<>();

        ProviderResponse response = provider().stream(request(), deltas::add);

        assertEquals(List.of("hello ", "from openai"), deltas);
        assertEquals("hello from openai", response.content());
        assertEquals(6, response.inputTokens());
        assertEquals(3, response.outputTokens());
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertTrue(sent.path("stream").asBoolean());
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean());
    }

    private OpenAiModelProvider provider() {
        LingShuProperties.OpenAi openai = new LingShuProperties.OpenAi();
        openai.setModel("shared-chat-model");
        openai.setUpstreamModel("gpt-4.1-mini");
        openai.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        openai.setApiKey("test-key");
        return new OpenAiModelProvider(openai, objectMapper);
    }

    private ProviderRequest request() {
        return new ProviderRequest(
                "trace-openai",
                "tenant-openai",
                "shared-chat-model",
                List.of(
                        new ChatMessage("system", "Answer concisely"),
                        new ChatMessage("user", "hello")
                ),
                0.3,
                128,
                0.8
        );
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requestBody.set(body);
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        boolean stream = body.contains("\"stream\":true");
        String response = stream
                ? "data: {\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"from openai\"},"
                + "\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":6,"
                + "\"completion_tokens\":3}}\n\n"
                + "data: [DONE]\n\n"
                : "{\"choices\":[{\"message\":{\"content\":\"hello from openai\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":6,"
                + "\"completion_tokens\":3}}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", stream
                ? "text/event-stream"
                : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
