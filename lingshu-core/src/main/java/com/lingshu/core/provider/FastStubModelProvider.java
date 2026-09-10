package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.provider.fast-stub",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class FastStubModelProvider implements ModelProvider {

    private static final String PROVIDER_ID = "stub-fast";

    private final String model;

    public FastStubModelProvider(LingShuProperties properties) {
        this.model = properties.getProvider().getFastStub().getModel();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> supportedModels() {
        return Set.of(model);
    }

    @Override
    public ProviderResponse invoke(ProviderRequest request) {
        String content = "stub-fast:" + request.prompt();
        return new ProviderResponse(
                PROVIDER_ID,
                model,
                content,
                estimateTokens(request.prompt()),
                estimateTokens(content),
                "stop"
        );
    }

    @Override
    public ProviderHealth health() {
        return new ProviderHealth(PROVIDER_ID, ProviderHealth.Status.UP, Instant.now());
    }

    private int estimateTokens(String text) {
        return Math.max(1, (text.codePointCount(0, text.length()) + 3) / 4);
    }
}
