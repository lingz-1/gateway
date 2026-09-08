package com.lingshu.core.tenant;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class TenantPolicyService {

    private final LingShuProperties properties;
    private final TenantPolicyStore store;
    private final List<TenantPolicySource> sources;

    public TenantPolicyService(LingShuProperties properties) {
        this(properties, new InMemoryTenantPolicyStore());
    }

    public TenantPolicyService(LingShuProperties properties, TenantPolicyStore store) {
        this(properties, store, List.of(store));
    }

    @Autowired
    public TenantPolicyService(
            LingShuProperties properties,
            TenantPolicyStore store,
            List<TenantPolicySource> sources
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.sources = List.copyOf(sources);
    }

    public TenantPolicy resolve(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        for (TenantPolicySource source : sources) {
            var policy = source.find(tenantId);
            if (policy.isPresent()) {
                return policy.get();
            }
        }
        return defaults(tenantId);
    }

    public TenantPolicy save(TenantPolicy policy) {
        return store.save(policy);
    }

    private TenantPolicy defaults(String tenantId) {
        LingShuProperties.Billing.Virtual billing = properties.getBilling().getVirtual();
        return new TenantPolicy(
                tenantId,
                true,
                Set.of(),
                true,
                properties.getCache().getExact().isEnabled(),
                properties.getCache().getSemantic().isEnabled(),
                0,
                0,
                billing.getInputPriceUsdPerMillion(),
                billing.getOutputPriceUsdPerMillion(),
                Instant.EPOCH
        );
    }
}
