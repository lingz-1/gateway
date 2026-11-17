package com.lingshu.core.processing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.core.cache.CacheEligibility;
import com.lingshu.core.cache.SemanticCacheService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Order(175)
public class SemanticCacheLookupProcessor implements ChatProcessor {

    private final SemanticCacheService cacheService;

    public SemanticCacheLookupProcessor(SemanticCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public String name() {
        return "semantic-cache-lookup";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return cacheService.isEnabled()
                && CacheEligibility.isSemanticCacheable(context.request())
                && (context.tenantPolicy() == null || context.tenantPolicy().semanticCacheEnabled())
                && !context.completed();
    }

    @Override
    public void process(ChatProcessingContext context) {
        long embeddingStartedAt = System.nanoTime();
        var embeddingResult = cacheService.tryEmbed(context.request());
        context.addStep(
                "semantic-cache-embedding",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - embeddingStartedAt)
        );
        embeddingResult.ifPresent(embedding -> {
            context.semanticEmbedding(embedding);
            long vectorQueryStartedAt = System.nanoTime();
            var matchResult = cacheService.find(context.tenantId(), context.request(), embedding);
            context.addStep(
                    "semantic-cache-vector-query",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - vectorQueryStartedAt)
            );
            matchResult
                    .ifPresent(match -> {
                        context.providerResponse(match.response());
                        context.cacheStatus(CacheStatus.SEMANTIC);
                    });
        });
    }
}
