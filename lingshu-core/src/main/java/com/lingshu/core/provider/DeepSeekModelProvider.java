package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.provider.deepseek",
        name = "enabled",
        havingValue = "true"
)
public class DeepSeekModelProvider implements ModelProvider {

    private static final String PROVIDER_ID = "deepseek";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Semaphore concurrency;
    private final int circuitFailureThreshold;
    private final Duration circuitOpenDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilEpochMs = new AtomicLong();

    @Autowired
    public DeepSeekModelProvider(LingShuProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, buildHttpClient(properties));
    }

    DeepSeekModelProvider(
            LingShuProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        LingShuProperties.DeepSeek deepseek = properties.getProvider().getDeepseek();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = chatCompletionsEndpoint(deepseek.getBaseUrl());
        this.model = deepseek.getModel();
        this.apiKey = deepseek.getApiKey();
        this.timeout = deepseek.getTimeout();
        this.maxAttempts = deepseek.getMaxAttempts();
        this.retryBackoff = deepseek.getRetryBackoff();
        this.concurrency = new Semaphore(deepseek.getMaxConcurrentRequests());
        this.circuitFailureThreshold = deepseek.getCircuitFailureThreshold();
        this.circuitOpenDuration = deepseek.getCircuitOpenDuration();
        validateConfiguration();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> supportedModels() {
        return Set.of(model);
    }

    @Override
    public ProviderResponse invoke(ProviderRequest request) {
        if (isCircuitOpen()) {
            throw new ProviderRoutingException("DeepSeek circuit breaker is open");
        }
        if (!concurrency.tryAcquire()) {
            throw new ProviderRoutingException("DeepSeek concurrency limit exceeded");
        }
        int accumulatedInputTokens = 0;
        int accumulatedOutputTokens = 0;
        try {
            Map<String, Object> requestFields = new LinkedHashMap<>();
            requestFields.put("model", wireModel());
            requestFields.put("messages", request.messages().stream()
                    .map(message -> Map.of(
                            "role", message.role(),
                            "content", message.content()
                    ))
                    .toList());
            requestFields.put("stream", false);
            if (request.temperature() != null) {
                requestFields.put("temperature", request.temperature());
            }
            String requestBody = objectMapper.writeValueAsString(requestFields);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<String> response;
                try {
                    response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                } catch (IOException exception) {
                    if (attempt < maxAttempts) {
                        pauseBeforeRetry(attempt);
                        continue;
                    }
                    throw new IllegalStateException("DeepSeek request failed", exception);
                }
                int[] usage = parseUsage(response.body());
                accumulatedInputTokens += usage[0];
                accumulatedOutputTokens += usage[1];
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    ProviderResponse parsed = parseResponse(response.body());
                    registerSuccess();
                    int priorInput = accumulatedInputTokens - parsed.inputTokens();
                    int priorOutput = accumulatedOutputTokens - parsed.outputTokens();
                    return new ProviderResponse(parsed.provider(), parsed.model(), parsed.content(),
                            parsed.inputTokens() + priorInput, parsed.outputTokens() + priorOutput);
                }
                if (isRetryable(response.statusCode()) && attempt < maxAttempts) {
                    pauseBeforeRetry(attempt);
                    continue;
                }
                registerFailure();
                throw new ProviderUsageException("DeepSeek provider returned HTTP " + response.statusCode(),
                        accumulatedInputTokens, accumulatedOutputTokens);
            }
            throw new IllegalStateException("DeepSeek request attempts exhausted");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            registerFailure();
            throw new IllegalStateException("DeepSeek request was interrupted", exception);
        } catch (ProviderUsageException | ProviderRoutingException exception) {
            throw exception;
        } catch (IllegalStateException exception) {
            registerFailure();
            throw exception;
        } catch (Exception exception) {
            registerFailure();
            throw new IllegalStateException("DeepSeek request failed", exception);
        } finally {
            concurrency.release();
        }
    }

    @Override
    public ProviderHealth health() {
        ProviderHealth.Status status = isCircuitOpen() ? ProviderHealth.Status.DOWN : ProviderHealth.Status.UP;
        return new ProviderHealth(PROVIDER_ID, status, Instant.now());
    }

    private ProviderResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("DeepSeek response does not contain choices");
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new IllegalStateException("DeepSeek response does not contain text content");
        }
        int[] usageTokens = parseUsage(root);
        return new ProviderResponse(
                PROVIDER_ID,
                model,
                content.asText(),
                usageTokens[0],
                usageTokens[1]
        );
    }

    private int[] parseUsage(String responseBody) {
        try {
            return parseUsage(objectMapper.readTree(responseBody));
        } catch (Exception ignored) {
            return new int[]{0, 0};
        }
    }

    private int[] parseUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        return new int[]{
                Math.max(0, usage.path("prompt_tokens").asInt(0)),
                Math.max(0, usage.path("completion_tokens").asInt(0))
        };
    }

    private void validateConfiguration() {
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("DeepSeek base URL must use HTTP or HTTPS");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("DeepSeek model must be configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DEEPSEEK_API_KEY must be configured");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("DeepSeek timeout must be positive");
        }
        if (maxAttempts < 1 || concurrency.availablePermits() < 1 || circuitFailureThreshold < 1) {
            throw new IllegalArgumentException("DeepSeek reliability limits must be positive");
        }
        if (retryBackoff.isNegative() || circuitOpenDuration.isNegative() || circuitOpenDuration.isZero()) {
            throw new IllegalArgumentException("DeepSeek retry and circuit durations are invalid");
        }
    }

    private static URI chatCompletionsEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("DeepSeek base URL must be configured");
        }
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        return URI.create(normalized + "/chat/completions");
    }

    private static HttpClient buildHttpClient(LingShuProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getProvider().getDeepseek().getTimeout())
                .build();
    }

    private String wireModel() {
        if ("deepseek-v4flash".equals(model)) {
            return "deepseek-v4-flash";
        }
        return model;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private void pauseBeforeRetry(int attempt) throws InterruptedException {
        long delayMs = Math.multiplyExact(retryBackoff.toMillis(), attempt);
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
    }

    private boolean isCircuitOpen() {
        return circuitOpenUntilEpochMs.get() > System.currentTimeMillis();
    }

    private void registerSuccess() {
        consecutiveFailures.set(0);
        circuitOpenUntilEpochMs.set(0);
    }

    private void registerFailure() {
        if (consecutiveFailures.incrementAndGet() >= circuitFailureThreshold) {
            circuitOpenUntilEpochMs.set(System.currentTimeMillis() + circuitOpenDuration.toMillis());
            consecutiveFailures.set(0);
        }
    }

}
