package com.lingshu.core.cache;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.cache.semantic",
        name = "embedding-provider",
        havingValue = "openai-compatible"
)
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int expectedDimension;
    private final int maxInputChars;

    public OpenAiCompatibleEmbeddingService(
            LingShuProperties properties,
            ObjectMapper objectMapper
    ) {
        this(properties, objectMapper, buildHttpClient(properties));
    }

    OpenAiCompatibleEmbeddingService(
            LingShuProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        LingShuProperties.Semantic semantic = properties.getCache().getSemantic();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(semantic.getEmbeddingEndpoint());
        this.model = semantic.getEmbeddingModel();
        this.apiKey = semantic.getEmbeddingApiKey();
        this.timeout = semantic.getEmbeddingTimeout();
        this.expectedDimension = semantic.getEmbeddingDimension();
        this.maxInputChars = semantic.getEmbeddingMaxInputChars();
        validateConfiguration();
    }

    @Override
    public double[] embed(String text) {
        if (text.isBlank()) {
            throw new IllegalArgumentException("Embedding input must not be blank");
        }
        if (text.length() > maxInputChars) {
            throw new IllegalArgumentException("Embedding input exceeds configured character limit");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "input", text
            ));
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            if (!apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Embedding provider returned HTTP " + response.statusCode()
                );
            }
            return parseEmbedding(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding request was interrupted", exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Embedding request failed", exception);
        }
    }

    private double[] parseEmbedding(String responseBody) throws Exception {
        JsonNode data = objectMapper.readTree(responseBody).path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("Embedding response does not contain data");
        }
        JsonNode values = data.get(0).path("embedding");
        if (!values.isArray() || values.size() != expectedDimension) {
            throw new IllegalStateException(
                    "Embedding dimension mismatch: expected " + expectedDimension
            );
        }
        double[] embedding = new double[expectedDimension];
        for (int index = 0; index < expectedDimension; index++) {
            JsonNode valueNode = values.get(index);
            if (!valueNode.isNumber()) {
                throw new IllegalStateException("Embedding response contains a non-numeric value");
            }
            double value = valueNode.asDouble();
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Embedding response contains a non-finite value");
            }
            embedding[index] = value;
        }
        return embedding;
    }

    private void validateConfiguration() {
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("Embedding endpoint must use HTTP or HTTPS");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("Embedding model must be configured");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Embedding timeout must be positive");
        }
        if (expectedDimension < 1) {
            throw new IllegalArgumentException("Embedding dimension must be positive");
        }
        if (maxInputChars < 1) {
            throw new IllegalArgumentException("Embedding input character limit must be positive");
        }
    }

    private static HttpClient buildHttpClient(LingShuProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getCache().getSemantic().getEmbeddingTimeout())
                .build();
    }
}
