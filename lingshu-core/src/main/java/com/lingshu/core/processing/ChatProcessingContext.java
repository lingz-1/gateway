package com.lingshu.core.processing;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.CacheStatus;
import com.lingshu.common.dto.ProcessingStep;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.billing.VirtualBillingCharge;
import com.lingshu.core.provider.ModelProvider;
import com.lingshu.core.tenant.TenantPolicy;
import com.lingshu.core.tenant.TenantRequestGuard;

import java.util.ArrayList;
import java.util.List;

public final class ChatProcessingContext {

    private ChatCompletionRequest request;
    private final String traceId;
    private final String tenantId;
    private final List<ProcessingStep> steps = new ArrayList<>();
    private ModelProvider provider;
    private ProviderResponse providerResponse;
    private CacheStatus cacheStatus = CacheStatus.MISS;
    private String exactCacheKey;
    private double[] semanticEmbedding;
    private VirtualBillingCharge virtualBillingCharge;
    private TenantPolicy tenantPolicy;
    private TenantRequestGuard.Permit policyPermit;

    public ChatProcessingContext(ChatCompletionRequest request, String traceId, String tenantId) {
        this.request = request;
        this.traceId = traceId;
        this.tenantId = tenantId;
    }

    public ChatCompletionRequest request() {
        return request;
    }

    public void request(ChatCompletionRequest request) {
        this.request = request;
    }

    public String traceId() {
        return traceId;
    }

    public String tenantId() {
        return tenantId;
    }

    public ModelProvider provider() {
        return provider;
    }

    public void provider(ModelProvider provider) {
        this.provider = provider;
    }

    public ProviderResponse providerResponse() {
        return providerResponse;
    }

    public void providerResponse(ProviderResponse providerResponse) {
        this.providerResponse = providerResponse;
    }

    public boolean completed() {
        return providerResponse != null;
    }

    public CacheStatus cacheStatus() {
        return cacheStatus;
    }

    public void cacheStatus(CacheStatus cacheStatus) {
        this.cacheStatus = cacheStatus;
    }

    public String exactCacheKey() {
        return exactCacheKey;
    }

    public void exactCacheKey(String exactCacheKey) {
        this.exactCacheKey = exactCacheKey;
    }

    public double[] semanticEmbedding() {
        return semanticEmbedding;
    }

    public void semanticEmbedding(double[] semanticEmbedding) {
        this.semanticEmbedding = semanticEmbedding;
    }

    public VirtualBillingCharge virtualBillingCharge() {
        return virtualBillingCharge;
    }

    public void virtualBillingCharge(VirtualBillingCharge virtualBillingCharge) {
        this.virtualBillingCharge = virtualBillingCharge;
    }

    public TenantPolicy tenantPolicy() {
        return tenantPolicy;
    }

    public void tenantPolicy(TenantPolicy tenantPolicy) {
        this.tenantPolicy = tenantPolicy;
    }

    public void policyPermit(TenantRequestGuard.Permit policyPermit) {
        this.policyPermit = policyPermit;
    }

    public void closePolicyPermit() {
        if (policyPermit != null) {
            policyPermit.close();
        }
    }

    public void addStep(String name, long durationMs) {
        steps.add(new ProcessingStep(name, durationMs));
    }

    public List<ProcessingStep> steps() {
        return List.copyOf(steps);
    }
}
