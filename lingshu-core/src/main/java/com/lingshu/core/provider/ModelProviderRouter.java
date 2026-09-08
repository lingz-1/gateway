package com.lingshu.core.provider;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ModelProviderRouter {

    private final Map<String, ModelProvider> providersByModel;

    public ModelProviderRouter(List<ModelProvider> providers) {
        Map<String, ModelProvider> routes = new HashMap<>();
        for (ModelProvider provider : providers) {
            for (String model : provider.supportedModels()) {
                ModelProvider previous = routes.putIfAbsent(model, provider);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate provider route for model: " + model);
                }
            }
        }
        this.providersByModel = Map.copyOf(routes);
    }

    public ModelProvider route(String model) {
        ModelProvider provider = providersByModel.get(model);
        if (provider == null) {
            throw new ProviderRoutingException("No provider is configured for model: " + model);
        }
        return provider;
    }
}
