package com.lingshu.core.cache;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SemanticCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticCacheService.class);

    private final boolean enabled;
    private final double similarityThreshold;
    private final Duration ttl;
    private final String promptVersion;
    private final String embeddingProvider;
    private final String embeddingModel;
    private final int embeddingDimension;
    private final EmbeddingService embeddingService;
    private final SemanticCacheStore store;
    private final ExactCacheKeyFactory exactCacheKeyFactory;

    public SemanticCacheService(
            LingShuProperties properties,
            EmbeddingService embeddingService,
            SemanticCacheStore store,
            ExactCacheKeyFactory exactCacheKeyFactory
    ) {
        LingShuProperties.Semantic semantic = properties.getCache().getSemantic();
        this.enabled = semantic.isEnabled();
        this.similarityThreshold = semantic.getSimilarityThreshold();
        this.ttl = semantic.getTtl();
        this.promptVersion = properties.getCache().getExact().getPromptVersion();
        this.embeddingProvider = semantic.getEmbeddingProvider();
        this.embeddingModel = semantic.getEmbeddingModel();
        this.embeddingDimension = semantic.getEmbeddingDimension();
        this.embeddingService = embeddingService;
        this.store = store;
        this.exactCacheKeyFactory = exactCacheKeyFactory;
        if (similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("Semantic similarity threshold must be between 0 and 1");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Semantic cache TTL must be positive");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double[] embed(ChatCompletionRequest request) {
        return embeddingService.embed(requestText(request));
    }

    public Optional<double[]> tryEmbed(ChatCompletionRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return Optional.of(embed(request));
        } catch (RuntimeException exception) {
            LOGGER.warn("Embedding request failed; continuing without semantic caching", exception);
            return Optional.empty();
        }
    }

    public Optional<SemanticCacheMatch> find(
            String tenantId,
            ChatCompletionRequest request,
            double[] embedding
    ) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return store.find(tenantId, scopeKey(request), embedding, similarityThreshold);
        } catch (RuntimeException exception) {
            LOGGER.warn("Semantic cache read failed; continuing as a cache miss", exception);
            return Optional.empty();
        }
    }

    public void put(
            String tenantId,
            ChatCompletionRequest request,
            double[] embedding,
            ProviderResponse response
    ) {
        if (!enabled) {
            return;
        }
        SemanticCacheEntry entry = new SemanticCacheEntry(
                tenantId,
                scopeKey(request),
                request.model(),
                promptVersion,
                exactCacheKeyFactory.create(tenantId, request),
                requestText(request),
                embedding,
                response,
                Instant.now().plus(ttl)
        );
        try {
            store.put(entry);
        } catch (RuntimeException exception) {
            LOGGER.warn("Semantic cache write failed; continuing without caching", exception);
        }
    }

    private String requestText(ChatCompletionRequest request) {
        return request.messages().stream()
                .map(message -> lengthPrefixed(message.role()) + lengthPrefixed(message.content()))
                .collect(Collectors.joining("\n"));
    }

    private String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    private String scopeKey(ChatCompletionRequest request) {
        String value = promptVersion
                + "\u0000" + request.model()
                + "\u0000" + Boolean.TRUE.equals(request.stream())
                + "\u0000" + (request.temperature() == null ? "default" : request.temperature())
                + "\u0000" + embeddingProvider
                + "\u0000" + embeddingModel
                + "\u0000" + embeddingDimension;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
