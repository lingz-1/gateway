package com.lingshu.core.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.cache.semantic",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class NoOpSemanticCacheStore implements SemanticCacheStore {

    @Override
    public Optional<SemanticCacheMatch> find(
            String tenantId,
            String scopeKey,
            double[] embedding,
            double similarityThreshold
    ) {
        return Optional.empty();
    }

    @Override
    public void put(SemanticCacheEntry entry) {
    }
}
