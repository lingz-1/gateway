package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelProviderRouterTest {

    @Test
    void supportsMultipleProvidersForOneLogicalModel() {
        TestProvider primary = provider("a-primary", ProviderHealth.Status.UP);
        TestProvider secondary = provider("b-secondary", ProviderHealth.Status.UP);

        ModelProviderRouter router = new ModelProviderRouter(List.of(secondary, primary));

        assertEquals(List.of(primary, secondary), router.routeCandidates("shared-model"));
        assertEquals(1, router.availableModels().size());
        assertEquals("a-primary", router.availableModels().getFirst().provider());
    }

    @Test
    void excludesUnhealthyProvidersAndRejectsWhenNoneAreHealthy() {
        TestProvider down = provider("a-down", ProviderHealth.Status.DOWN);
        TestProvider up = provider("b-up", ProviderHealth.Status.UP);
        ModelProviderRouter router = new ModelProviderRouter(List.of(down, up));

        assertEquals(List.of(up), router.routeCandidates("shared-model"));

        ModelProviderRouter unavailable = new ModelProviderRouter(List.of(down));
        assertThrows(ProviderRoutingException.class, () -> unavailable.route("shared-model"));
    }

    @Test
    void treatsHealthCheckFailuresAsDown() {
        ModelProvider brokenHealth = new TestProvider("a-broken", ProviderHealth.Status.UP) {
            @Override
            public ProviderHealth health() {
                throw new IllegalStateException("health check failed");
            }
        };
        TestProvider healthy = provider("b-healthy", ProviderHealth.Status.UP);
        ModelProviderRouter router = new ModelProviderRouter(List.of(brokenHealth, healthy));

        assertEquals(healthy, router.route("shared-model"));
    }

    @Test
    void reordersCandidatesUsingLatencyLoadAndFailureSignals() {
        TestProvider primary = provider("a-primary", ProviderHealth.Status.UP);
        TestProvider secondary = provider("b-secondary", ProviderHealth.Status.UP);
        ModelProviderRouter router = new ModelProviderRouter(List.of(primary, secondary));

        router.recordSuccess(primary, System.nanoTime() - 50_000_000L);
        router.recordSuccess(secondary, System.nanoTime() - 10_000_000L);
        assertEquals(secondary, router.route("shared-model"));

        long attempt = router.beginAttempt(secondary);
        assertEquals(primary, router.route("shared-model"));
        router.recordFailure(secondary, attempt);
        assertEquals(primary, router.route("shared-model"));
    }

    private TestProvider provider(String id, ProviderHealth.Status status) {
        return new TestProvider(id, status);
    }

    private static class TestProvider implements ModelProvider {

        private final String id;
        private final ProviderHealth.Status status;

        private TestProvider(String id, ProviderHealth.Status status) {
            this.id = id;
            this.status = status;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> supportedModels() {
            return Set.of("shared-model");
        }

        @Override
        public ProviderResponse invoke(ProviderRequest request) {
            return new ProviderResponse(id, request.requestedModel(), "ok", 1, 1);
        }

        @Override
        public ProviderHealth health() {
            return new ProviderHealth(id, status, Instant.now());
        }
    }
}
