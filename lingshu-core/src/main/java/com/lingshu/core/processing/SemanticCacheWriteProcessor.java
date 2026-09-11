package com.lingshu.core.processing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.core.cache.CacheEligibility;
import com.lingshu.core.cache.SemanticCacheService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(390)
public class SemanticCacheWriteProcessor implements ChatProcessor {

    private final SemanticCacheService cacheService;

    public SemanticCacheWriteProcessor(SemanticCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public String name() {
        return "semantic-cache-write";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return cacheService.isEnabled()
                && CacheEligibility.isSemanticCacheable(context.request())
                && (context.tenantPolicy() == null || context.tenantPolicy().semanticCacheEnabled())
                && context.cacheStatus() == CacheStatus.MISS
                && context.completed()
                && context.semanticEmbedding() != null;
    }

    @Override
    public void process(ChatProcessingContext context) {
        cacheService.put(
                context.tenantId(),
                context.request(),
                context.semanticEmbedding(),
                context.providerResponse()
        );
    }
}
