package com.lingshu.core.cache;

import java.util.Optional;

public interface SemanticCacheStore {

    Optional<SemanticCacheMatch> find(
            String tenantId,
            String scopeKey,
            double[] embedding,
            double similarityThreshold
    );

    void put(SemanticCacheEntry entry);
}
