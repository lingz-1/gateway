package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;

import java.util.Set;

public interface ModelProvider {

    String id();

    Set<String> supportedModels();

    ProviderResponse invoke(ProviderRequest request);

    ProviderHealth health();
}
