package com.lingshu.core.processing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.core.cache.ExactCacheService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(150)
public class ExactCacheLookupProcessor implements ChatProcessor {

    private final ExactCacheService cacheService;

    public ExactCacheLookupProcessor(ExactCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public String name() {
        return "exact-cache-lookup";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return context.tenantPolicy() == null || context.tenantPolicy().exactCacheEnabled();
    }

    @Override
    public void process(ChatProcessingContext context) {
        String key = cacheService.key(context.tenantId(), context.request());
        context.exactCacheKey(key);
        cacheService.get(key).ifPresent(response -> {
            context.providerResponse(response);
            context.cacheStatus(CacheStatus.EXACT);
        });
    }
}
