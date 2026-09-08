package com.lingshu.core.cache;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
        havingValue = "tei"
)
public class TeiEmbeddingService implements EmbeddingService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration timeout;
    private final int expectedDimension;
    private final int maxInputChars;

    @Autowired
    public TeiEmbeddingService(
            LingShuProperties properties,
            ObjectMapper objectMapper
    ) {
        this(properties, objectMapper, buildHttpClient(properties));
    }

    TeiEmbeddingService(
            LingShuProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        LingShuProperties.Semantic semantic = properties.getCache().getSemantic();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(semantic.getEmbeddingEndpoint());
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
            String requestBody = objectMapper.writeValueAsString(Map.of("inputs", text));
            HttpRequest responseRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    responseRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("TEI provider returned HTTP " + response.statusCode());
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
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode values = root;
        if (root.isArray() && root.size() > 0 && root.get(0).isArray()) {
            values = root.get(0);
        }
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
