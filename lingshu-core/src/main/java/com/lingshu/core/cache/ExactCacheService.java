package com.lingshu.core.cache;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ExactCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExactCacheService.class);

    private final boolean enabled;
    private final ExactCacheKeyFactory keyFactory;
    private final ExactCacheStore store;

    public ExactCacheService(
            LingShuProperties properties,
            ExactCacheKeyFactory keyFactory,
            ExactCacheStore store
    ) {
        this.enabled = properties.getCache().getExact().isEnabled();
        this.keyFactory = keyFactory;
        this.store = store;
    }

    public String key(String tenantId, ChatCompletionRequest request) {
        return keyFactory.create(tenantId, request);
    }

    public Optional<ProviderResponse> get(String key) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return store.get(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Exact cache read failed; continuing as a cache miss", exception);
            return Optional.empty();
        }
    }

    public void put(String key, ProviderResponse response) {
        if (enabled) {
            try {
                store.put(key, response);
            } catch (RuntimeException exception) {
                LOGGER.warn("Exact cache write failed; continuing without caching", exception);
            }
        }
    }
}
