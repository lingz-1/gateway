package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    private final String providerId;
    private final URI endpoint;
    private final String model;
    private final String wireModel;
    private final String apiKey;
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Semaphore concurrency;
    private final int maxStreamResponseChars;
    private final int circuitFailureThreshold;
    private final Duration circuitOpenDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilEpochMs = new AtomicLong();

    @Autowired
    public DeepSeekModelProvider(LingShuProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, buildHttpClient(properties.getProvider().getDeepseek()));
    }

    DeepSeekModelProvider(
            LingShuProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this(
                properties.getProvider().getDeepseek(),
                objectMapper,
                httpClient,
                PROVIDER_ID
        );
    }

    DeepSeekModelProvider(
            LingShuProperties.HttpChatProvider provider,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            String providerId
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.providerId = providerId;
        this.endpoint = chatCompletionsEndpoint(provider.getBaseUrl());
        this.model = provider.getModel();
        this.wireModel = provider.getUpstreamModel();
        this.apiKey = provider.getApiKey();
        this.timeout = provider.getTimeout();
        this.maxAttempts = provider.getMaxAttempts();
        this.retryBackoff = provider.getRetryBackoff();
        this.concurrency = new Semaphore(provider.getMaxConcurrentRequests());
        this.maxStreamResponseChars = provider.getMaxStreamResponseChars();
        this.circuitFailureThreshold = provider.getCircuitFailureThreshold();
        this.circuitOpenDuration = provider.getCircuitOpenDuration();
        validateConfiguration();
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public Set<String> supportedModels() {
        return Set.of(model);
    }

    @Override
    public ProviderResponse invoke(ProviderRequest request) {
        if (isCircuitOpen()) {
            throw new ProviderRoutingException(providerId + " circuit breaker is open");
        }
        if (!concurrency.tryAcquire()) {
            throw new ProviderRoutingException(providerId + " concurrency limit exceeded");
        }
        int accumulatedInputTokens = 0;
        int accumulatedOutputTokens = 0;
        try {
            String requestBody = objectMapper.writeValueAsString(requestFields(request, false));
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
                    throw new IllegalStateException(providerId + " request failed", exception);
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
                            parsed.inputTokens() + priorInput, parsed.outputTokens() + priorOutput,
                            parsed.finishReason());
                }
                if (isRetryable(response.statusCode()) && attempt < maxAttempts) {
                    pauseBeforeRetry(attempt);
                    continue;
                }
                registerFailure();
                throw new ProviderUsageException(providerId + " provider returned HTTP " + response.statusCode(),
                        accumulatedInputTokens, accumulatedOutputTokens);
            }
            throw new IllegalStateException(providerId + " request attempts exhausted");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            registerFailure();
            throw new IllegalStateException(providerId + " request was interrupted", exception);
        } catch (ProviderUsageException | ProviderRoutingException exception) {
            throw exception;
        } catch (IllegalStateException exception) {
            registerFailure();
            throw exception;
        } catch (Exception exception) {
            registerFailure();
            throw new IllegalStateException(providerId + " request failed", exception);
        } finally {
            concurrency.release();
        }
    }

    @Override
    public ProviderResponse stream(ProviderRequest request, ProviderStreamConsumer consumer) {
        if (isCircuitOpen()) {
            throw new ProviderRoutingException(providerId + " circuit breaker is open");
        }
        if (!concurrency.tryAcquire()) {
            throw new ProviderRoutingException(providerId + " concurrency limit exceeded");
        }
        int accumulatedInputTokens = 0;
        int accumulatedOutputTokens = 0;
        try {
            String requestBody = objectMapper.writeValueAsString(requestFields(request, true));
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<InputStream> response;
                try {
                    response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                } catch (IOException exception) {
                    if (attempt < maxAttempts) {
                        pauseBeforeRetry(attempt);
                        continue;
                    }
                    throw new IllegalStateException(providerId + " streaming request failed", exception);
                }
                try (InputStream responseBody = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        String errorBody = new String(responseBody.readAllBytes(), StandardCharsets.UTF_8);
                        int[] usage = parseUsage(errorBody);
                        accumulatedInputTokens += usage[0];
                        accumulatedOutputTokens += usage[1];
                        if (isRetryable(response.statusCode()) && attempt < maxAttempts) {
                            pauseBeforeRetry(attempt);
                            continue;
                        }
                        registerFailure();
                        throw new ProviderUsageException(
                                providerId + " provider returned HTTP " + response.statusCode(),
                                accumulatedInputTokens,
                                accumulatedOutputTokens
                        );
                    }

                    StreamAccumulator stream = new StreamAccumulator();
                    consumer.onOpen(() -> closeQuietly(responseBody));
                    try {
                        readStream(responseBody, consumer, stream);
                    } catch (ResponseLimitExceededException exception) {
                        registerFailure();
                        throw new ProviderUsageException(
                                exception.getMessage(),
                                accumulatedInputTokens + estimateTokens(request.prompt()),
                                accumulatedOutputTokens + estimateTokens(stream.content.toString())
                        );
                    } catch (IOException exception) {
                        if (consumer.isCancelled()) {
                            throw new ProviderStreamCancelledException(
                                    "Provider stream consumer is unavailable",
                                    exception
                            );
                        }
                        if (!stream.emitted && attempt < maxAttempts) {
                            pauseBeforeRetry(attempt);
                            continue;
                        }
                        throw new IllegalStateException(providerId + " response stream failed", exception);
                    }
                    if (consumer.isCancelled()) {
                        throw new ProviderStreamCancelledException(
                                "Provider stream consumer is unavailable",
                                new IOException("Provider stream was cancelled")
                        );
                    }
                    registerSuccess();
                    String content = stream.content.toString();
                    int inputTokens = stream.usageReported
                            ? stream.inputTokens
                            : estimateTokens(request.prompt());
                    int outputTokens = stream.usageReported
                            ? stream.outputTokens
                            : estimateTokens(content);
                    return new ProviderResponse(
                            providerId,
                            model,
                            content,
                            inputTokens + accumulatedInputTokens,
                            outputTokens + accumulatedOutputTokens,
                            stream.finishReason
                    );
                }
            }
            throw new IllegalStateException(providerId + " streaming request attempts exhausted");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            registerFailure();
            throw new IllegalStateException(providerId + " streaming request was interrupted", exception);
        } catch (ProviderStreamCancelledException exception) {
            throw exception;
        } catch (ProviderUsageException | ProviderRoutingException exception) {
            throw exception;
        } catch (IllegalStateException exception) {
            registerFailure();
            throw exception;
        } catch (Exception exception) {
            registerFailure();
            throw new IllegalStateException(providerId + " streaming request failed", exception);
        } finally {
            concurrency.release();
        }
    }

    @Override
    public ProviderHealth health() {
        ProviderHealth.Status status = isCircuitOpen() ? ProviderHealth.Status.DOWN : ProviderHealth.Status.UP;
        return new ProviderHealth(providerId, status, Instant.now());
    }

    private ProviderResponse parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException(providerId + " response does not contain choices");
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new IllegalStateException(providerId + " response does not contain text content");
        }
        JsonNode finishReason = choices.get(0).path("finish_reason");
        int[] usageTokens = parseUsage(root);
        return new ProviderResponse(
                providerId,
                model,
                content.asText(),
                usageTokens[0],
                usageTokens[1],
                finishReason.isTextual() ? finishReason.asText() : "stop"
        );
    }

    private Map<String, Object> requestFields(ProviderRequest request, boolean stream) {
        Map<String, Object> requestFields = new LinkedHashMap<>();
        requestFields.put("model", wireModel);
        requestFields.put("messages", request.messages().stream()
                .map(message -> Map.of(
                        "role", message.role(),
                        "content", message.content()
                ))
                .toList());
        requestFields.put("stream", stream);
        if (stream) {
            requestFields.put("stream_options", Map.of("include_usage", true));
        }
        if (request.temperature() != null) {
            requestFields.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            requestFields.put("max_tokens", request.maxTokens());
        }
        if (request.topP() != null) {
            requestFields.put("top_p", request.topP());
        }
        return requestFields;
    }

    private void readStream(
            InputStream responseBody,
            ProviderStreamConsumer consumer,
            StreamAccumulator stream
    ) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).stripLeading();
                if (data.isBlank()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    return;
                }
                JsonNode root = objectMapper.readTree(data);
                if (!root.path("error").isMissingNode()) {
                    throw new IllegalStateException(providerId + " stream returned an error event");
                }
                JsonNode choices = root.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    JsonNode choice = choices.get(0);
                    JsonNode content = choice.path("delta").path("content");
                    if (content.isTextual() && !content.asText().isEmpty()) {
                        String delta = content.asText();
                        if ((long) stream.content.length() + delta.length() > maxStreamResponseChars) {
                            throw new ResponseLimitExceededException(
                                    providerId + " streamed response exceeded "
                                            + maxStreamResponseChars + " characters"
                            );
                        }
                        try {
                            consumer.onDelta(delta);
                        } catch (IOException exception) {
                            throw new ProviderStreamCancelledException(
                                    "Provider stream consumer is unavailable",
                                    exception
                            );
                        }
                        stream.content.append(delta);
                        stream.emitted = true;
                    }
                    JsonNode finishReason = choice.path("finish_reason");
                    if (finishReason.isTextual() && !finishReason.asText().isBlank()) {
                        stream.finishReason = finishReason.asText();
                    }
                }
                JsonNode usageNode = root.path("usage");
                if (usageNode.isObject()) {
                    int[] usage = parseUsage(root);
                    stream.inputTokens = usage[0];
                    stream.outputTokens = usage[1];
                    stream.usageReported = true;
                }
            }
        }
    }

    private void closeQuietly(InputStream responseBody) {
        try {
            responseBody.close();
        } catch (IOException ignored) {
            // The upstream stream is already closed.
        }
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
            throw new IllegalArgumentException(providerId + " base URL must use HTTP or HTTPS");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(providerId + " model must be configured");
        }
        if (wireModel == null || wireModel.isBlank()) {
            throw new IllegalArgumentException(providerId + " upstream model must be configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(providerId + " API key must be configured");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(providerId + " timeout must be positive");
        }
        if (maxAttempts < 1 || concurrency.availablePermits() < 1
                || maxStreamResponseChars < 1 || circuitFailureThreshold < 1) {
            throw new IllegalArgumentException(providerId + " reliability limits must be positive");
        }
        if (retryBackoff.isNegative() || circuitOpenDuration.isNegative() || circuitOpenDuration.isZero()) {
            throw new IllegalArgumentException(providerId + " retry and circuit durations are invalid");
        }
    }

    private static URI chatCompletionsEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Provider base URL must be configured");
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

    static HttpClient buildHttpClient(LingShuProperties.HttpChatProvider provider) {
        return HttpClient.newBuilder()
                .connectTimeout(provider.getTimeout())
                .build();
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

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (text.codePointCount(0, text.length()) + 3) / 4);
    }

    private static final class StreamAccumulator {

        private final StringBuilder content = new StringBuilder();
        private boolean emitted;
        private boolean usageReported;
        private int inputTokens;
        private int outputTokens;
        private String finishReason = "stop";
    }

    private static final class ResponseLimitExceededException extends IllegalStateException {

        private ResponseLimitExceededException(String message) {
            super(message);
        }
    }

}
