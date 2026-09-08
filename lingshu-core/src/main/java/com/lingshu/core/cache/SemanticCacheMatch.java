package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;

public record SemanticCacheMatch(ProviderResponse response, double similarity) {
}
