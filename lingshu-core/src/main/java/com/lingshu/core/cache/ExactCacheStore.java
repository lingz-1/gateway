package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;

import java.util.Optional;

public interface ExactCacheStore {

    Optional<ProviderResponse> get(String key);

    void put(String key, ProviderResponse response);
}
