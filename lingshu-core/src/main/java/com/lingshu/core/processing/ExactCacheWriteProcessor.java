package com.lingshu.core.processing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.core.cache.ExactCacheService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(400)
public class ExactCacheWriteProcessor implements ChatProcessor {

    private final ExactCacheService cacheService;

    public ExactCacheWriteProcessor(ExactCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public String name() {
        return "exact-cache-write";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return (context.tenantPolicy() == null || context.tenantPolicy().exactCacheEnabled())
                && context.cacheStatus() != CacheStatus.EXACT && context.completed();
    }

    @Override
    public void process(ChatProcessingContext context) {
        cacheService.put(context.exactCacheKey(), context.providerResponse());
    }
}
