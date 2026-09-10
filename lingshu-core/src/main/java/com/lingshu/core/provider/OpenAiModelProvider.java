package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.provider.openai",
        name = "enabled",
        havingValue = "true"
)
public class OpenAiModelProvider implements ModelProvider {

    private static final String PROVIDER_ID = "openai";

    private final DeepSeekModelProvider delegate;

    @Autowired
    public OpenAiModelProvider(LingShuProperties properties, ObjectMapper objectMapper) {
        this(properties.getProvider().getOpenai(), objectMapper);
    }

    OpenAiModelProvider(LingShuProperties.OpenAi openai, ObjectMapper objectMapper) {
        this.delegate = new DeepSeekModelProvider(
                openai,
                objectMapper,
                buildHttpClient(openai),
                PROVIDER_ID
        );
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public Set<String> supportedModels() {
        return delegate.supportedModels();
    }

    @Override
    public ProviderResponse invoke(ProviderRequest request) {
        return delegate.invoke(request);
    }

    @Override
    public ProviderResponse stream(ProviderRequest request, ProviderStreamConsumer consumer) {
        return delegate.stream(request, consumer);
    }

    @Override
    public ProviderHealth health() {
        return delegate.health();
    }

    private static java.net.http.HttpClient buildHttpClient(LingShuProperties.OpenAi openai) {
        return DeepSeekModelProvider.buildHttpClient(openai);
    }
}
