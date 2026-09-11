package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;

import java.io.IOException;
import java.util.Set;

public interface ModelProvider {

    String id();

    Set<String> supportedModels();

    ProviderResponse invoke(ProviderRequest request);

    default ProviderResponse stream(ProviderRequest request, ProviderStreamConsumer consumer) {
        ProviderResponse response = invoke(request);
        try {
            if (response.content() != null && !response.content().isEmpty()) {
                consumer.onDelta(response.content());
            }
            for (int index = 0; index < response.toolCalls().size(); index++) {
                var toolCall = response.toolCalls().get(index);
                consumer.onToolCallDelta(
                        index,
                        toolCall.id(),
                        toolCall.type(),
                        toolCall.function().name(),
                        toolCall.function().arguments()
                );
            }
        } catch (IOException exception) {
            throw new ProviderStreamCancelledException("Provider stream consumer is unavailable", exception);
        }
        return response;
    }

    ProviderHealth health();
}
