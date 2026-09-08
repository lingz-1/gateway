package com.lingshu.core.tenant;

import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantPolicyServiceTest {

    @Test
    void usesGlobalDefaultsWhenTenantHasNoPersistedPolicy() {
        TenantPolicy policy = new TenantPolicyService(new LingShuProperties()).resolve("tenant-a");

        assertTrue(policy.enabled());
        assertTrue(policy.allowedModels().isEmpty());
        assertTrue(policy.exactCacheEnabled());
        assertEquals(new BigDecimal("0.22"), policy.inputPriceUsdPerMillion());
    }

    @Test
    void loadsAndSavesPersistedPolicy() {
        TenantPolicy expected = policy("tenant-a");
        FakeStore store = new FakeStore(expected);
        TenantPolicyService service = new TenantPolicyService(new LingShuProperties(), store);

        assertEquals(expected, service.resolve("tenant-a"));
        assertEquals(expected, service.save(expected));
    }

    @Test
    void appliesRuntimeUpdatesWithDefaultInMemoryStore() {
        TenantPolicyService service = new TenantPolicyService(new LingShuProperties());
        TenantPolicy updated = policy("tenant-runtime");

        service.save(updated);

        assertEquals(updated, service.resolve("tenant-runtime"));
    }

    @Test
    void givesRemotePolicySourcePrecedenceOverLocalStore() {
        TenantPolicy local = policy("tenant-priority");
        TenantPolicy remote = new TenantPolicy(
                "tenant-priority",
                false,
                Set.of("stub-fast-v1"),
                false,
                false,
                false,
                1,
                1,
                new BigDecimal("0.3"),
                new BigDecimal("0.4"),
                Instant.now()
        );
        FakeStore store = new FakeStore(local);
        TenantPolicySource remoteSource = tenantId -> Optional.of(remote)
                .filter(value -> value.tenantId().equals(tenantId));
        TenantPolicyService service = new TenantPolicyService(
                new LingShuProperties(),
                store,
                java.util.List.of(remoteSource, store)
        );

        assertEquals(remote, service.resolve("tenant-priority"));
    }

    private TenantPolicy policy(String tenantId) {
        return new TenantPolicy(tenantId, true, Set.of("stub-echo-v1"), true, true, false,
                10, 2, new BigDecimal("0.1"), new BigDecimal("0.2"), Instant.now());
    }

    private static final class FakeStore implements TenantPolicyStore {
        private TenantPolicy policy;

        private FakeStore(TenantPolicy policy) {
            this.policy = policy;
        }

        @Override
        public Optional<TenantPolicy> find(String tenantId) {
            return Optional.ofNullable(policy).filter(value -> value.tenantId().equals(tenantId));
        }

        @Override
        public TenantPolicy save(TenantPolicy policy) {
            this.policy = policy;
            return policy;
        }
    }
}
