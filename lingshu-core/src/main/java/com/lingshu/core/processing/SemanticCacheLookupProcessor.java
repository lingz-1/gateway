package com.lingshu.core.processing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.core.cache.SemanticCacheService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
                && (context.tenantPolicy() == null || context.tenantPolicy().semanticCacheEnabled())
                && !context.completed();
    }

    @Override
    public void process(ChatProcessingContext context) {
        cacheService.tryEmbed(context.request()).ifPresent(embedding -> {
            context.semanticEmbedding(embedding);
            cacheService.find(context.tenantId(), context.request(), embedding)
                    .ifPresent(match -> {
                        context.providerResponse(match.response());
                        context.cacheStatus(CacheStatus.SEMANTIC);
                    });
        });
    }
}
