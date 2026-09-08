package com.lingshu.core.cache;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleEmbeddingServiceTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>(
            "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}"
    );
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", this::handleRequest);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsCompatibleRequestAndParsesEmbedding() throws Exception {
        OpenAiCompatibleEmbeddingService service = service(properties(3));

        double[] result = service.embed("hello world");

        assertArrayEquals(new double[]{0.1, 0.2, 0.3}, result);
        assertEquals("Bearer test-key", authorization.get());
        JsonNode request = objectMapper.readTree(requestBody.get());
        assertEquals("embedding-test-model", request.path("model").asText());
        assertEquals("hello world", request.path("input").asText());
    }

    @Test
    void rejectsUnexpectedEmbeddingDimension() {
        responseBody.set("{\"data\":[{\"embedding\":[0.1,0.2]}]}");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service(properties(3)).embed("hello")
        );

        assertTrue(exception.getMessage().contains("dimension mismatch"));
    }

    @Test
    void rejectsProviderHttpErrorWithoutExposingBody() {
        responseStatus.set(429);
        responseBody.set("{\"error\":\"sensitive provider detail\"}");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service(properties(3)).embed("hello")
        );

        assertEquals("Embedding provider returned HTTP 429", exception.getMessage());
    }

    @Test
    void rejectsNonNumericEmbeddingValues() {
        responseBody.set("{\"data\":[{\"embedding\":[0.1,\"bad\",0.3]}]}");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service(properties(3)).embed("hello")
        );

        assertTrue(exception.getMessage().contains("non-numeric"));
    }

    @Test
    void enforcesInputCharacterLimitBeforeCallingProvider() {
        LingShuProperties properties = properties(3);
        properties.getCache().getSemantic().setEmbeddingMaxInputChars(5);

        assertThrows(
                IllegalArgumentException.class,
                () -> service(properties).embed("123456")
        );
        assertNull(requestBody.get());
    }

    private OpenAiCompatibleEmbeddingService service(LingShuProperties properties) {
        return new OpenAiCompatibleEmbeddingService(
                properties,
                objectMapper,
                HttpClient.newHttpClient()
        );
    }

    private LingShuProperties properties(int dimension) {
        LingShuProperties properties = new LingShuProperties();
        LingShuProperties.Semantic semantic = properties.getCache().getSemantic();
        semantic.setEmbeddingProvider("openai-compatible");
        semantic.setEmbeddingEndpoint(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings"
        );
        semantic.setEmbeddingModel("embedding-test-model");
        semantic.setEmbeddingApiKey("test-key");
        semantic.setEmbeddingDimension(dimension);
        return properties;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
