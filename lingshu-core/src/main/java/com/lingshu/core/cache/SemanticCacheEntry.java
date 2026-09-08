package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;

import java.time.Instant;

public record SemanticCacheEntry(
        String tenantId,
        String scopeKey,
        String model,
        String promptVersion,
        String requestHash,
        String requestText,
        double[] embedding,
        ProviderResponse response,
        Instant expiresAt
) {
}
