package com.lingshu.core.api;

import com.lingshu.core.tenant.TenantPolicy;
import com.lingshu.core.tenant.TenantPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

@RestController
@RequestMapping("/internal/tenants")
public class TenantPolicyController {

    private final TenantPolicyService policyService;

    public TenantPolicyController(TenantPolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping("/{tenantId}/policy")
    public TenantPolicy get(@PathVariable String tenantId) {
        return policyService.resolve(tenantId);
    }

    @PutMapping("/{tenantId}/policy")
    public TenantPolicy put(@PathVariable String tenantId, @Valid @RequestBody UpdateRequest request) {
        return policyService.save(new TenantPolicy(
                tenantId,
                request.enabled(),
                request.allowedModels(),
                request.piiRedactionEnabled(),
                request.exactCacheEnabled(),
                request.semanticCacheEnabled(),
                request.requestsPerMinute(),
                request.maxConcurrentRequests(),
                request.inputPriceUsdPerMillion(),
                request.outputPriceUsdPerMillion(),
                Instant.now()
        ));
    }

    public record UpdateRequest(
            boolean enabled,
            @NotNull Set<String> allowedModels,
            boolean piiRedactionEnabled,
            boolean exactCacheEnabled,
            boolean semanticCacheEnabled,
            @Min(0) int requestsPerMinute,
            @Min(0) int maxConcurrentRequests,
            @NotNull @DecimalMin("0") BigDecimal inputPriceUsdPerMillion,
            @NotNull @DecimalMin("0") BigDecimal outputPriceUsdPerMillion
    ) {
    }
}
