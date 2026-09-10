package com.lingshu.core.provider;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ModelProviderRouter {

    private static final long DEFAULT_LATENCY_NANOS = 100_000_000L;
    private static final long IN_FLIGHT_PENALTY_NANOS = 100_000_000L;
    private static final long FAILURE_PENALTY_NANOS = 1_000_000_000L;

    private final Map<String, List<ModelProvider>> providersByModel;
    private final ConcurrentMap<ModelProvider, ProviderStats> providerStats = new ConcurrentHashMap<>();

    public ModelProviderRouter(List<ModelProvider> providers) {
        Map<String, List<ModelProvider>> routes = new HashMap<>();
        for (ModelProvider provider : providers) {
            providerStats.put(provider, new ProviderStats());
            for (String model : provider.supportedModels()) {
                routes.computeIfAbsent(model, ignored -> new java.util.ArrayList<>()).add(provider);
            }
        }
        Map<String, List<ModelProvider>> immutableRoutes = new HashMap<>();
        routes.forEach((model, candidates) -> immutableRoutes.put(
                model,
                candidates.stream().sorted(Comparator.comparing(ModelProvider::id)).toList()
        ));
        this.providersByModel = Map.copyOf(immutableRoutes);
    }

    public ModelProvider route(String model) {
        return routeCandidates(model).getFirst();
    }

    public List<ModelProvider> routeCandidates(String model) {
        List<ModelProvider> providers = providersByModel.get(model);
        if (providers == null || providers.isEmpty()) {
            throw new ProviderRoutingException("No provider is configured for model: " + model);
        }
        List<ModelProvider> healthy = providers.stream()
                .filter(this::isHealthy)
                .sorted(Comparator.comparingLong(this::routingScore)
                        .thenComparing(ModelProvider::id))
                .toList();
        if (healthy.isEmpty()) {
            throw new ProviderRoutingException("No healthy provider is available for model: " + model);
        }
        return healthy;
    }

    public long beginAttempt(ModelProvider provider) {
        stats(provider).inFlight.incrementAndGet();
        return System.nanoTime();
    }

    public void recordSuccess(ModelProvider provider, long startedAtNanos) {
        ProviderStats stats = stats(provider);
        stats.inFlight.updateAndGet(value -> Math.max(0, value - 1));
        stats.consecutiveFailures.set(0);
        updateLatency(stats, startedAtNanos);
    }

    public void recordFailure(ModelProvider provider, long startedAtNanos) {
        ProviderStats stats = stats(provider);
        stats.inFlight.updateAndGet(value -> Math.max(0, value - 1));
        stats.consecutiveFailures.incrementAndGet();
        updateLatency(stats, startedAtNanos);
    }

    public void recordCancellation(ModelProvider provider) {
        stats(provider).inFlight.updateAndGet(value -> Math.max(0, value - 1));
    }

    public List<AvailableModel> availableModels() {
        return providersByModel.entrySet().stream()
                .map(entry -> {
                    List<ModelProvider> providers = entry.getValue();
                    ModelProvider provider = providers.stream()
                            .filter(this::isHealthy)
                            .min(Comparator.comparingLong(this::routingScore)
                                    .thenComparing(ModelProvider::id))
                            .orElse(providers.getFirst());
                    return new AvailableModel(entry.getKey(), provider.id(), healthStatus(provider));
                })
                .sorted(Comparator.comparing(AvailableModel::id))
                .toList();
    }

    private boolean isHealthy(ModelProvider provider) {
        return healthStatus(provider) == ProviderHealth.Status.UP;
    }

    private ProviderHealth.Status healthStatus(ModelProvider provider) {
        try {
            return provider.health().status();
        } catch (RuntimeException ignored) {
            return ProviderHealth.Status.DOWN;
        }
    }

    private ProviderStats stats(ModelProvider provider) {
        return providerStats.computeIfAbsent(provider, ignored -> new ProviderStats());
    }

    private long routingScore(ModelProvider provider) {
        ProviderStats stats = stats(provider);
        long latency = stats.ewmaLatencyNanos.get();
        if (latency == 0) {
            latency = DEFAULT_LATENCY_NANOS;
        }
        return latency
                + (long) stats.inFlight.get() * IN_FLIGHT_PENALTY_NANOS
                + (long) stats.consecutiveFailures.get() * FAILURE_PENALTY_NANOS;
    }

    private void updateLatency(ProviderStats stats, long startedAtNanos) {
        long sample = Math.max(1, System.nanoTime() - startedAtNanos);
        stats.ewmaLatencyNanos.updateAndGet(previous -> previous == 0
                ? sample
                : (previous * 8 + sample * 2) / 10);
    }

    private static final class ProviderStats {

        private final AtomicLong ewmaLatencyNanos = new AtomicLong();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
    }

    public record AvailableModel(
            String id,
            String provider,
            ProviderHealth.Status status
    ) {
    }
}
