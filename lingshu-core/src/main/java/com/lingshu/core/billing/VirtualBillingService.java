package com.lingshu.core.billing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import com.lingshu.core.tenant.TenantPolicy;
import com.lingshu.core.tenant.TenantPolicyService;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class VirtualBillingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VirtualBillingService.class);

    private static final long NANOS_PER_CNY = 1_000_000_000L;
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final LingShuProperties.Billing.Virtual properties;
    private final VirtualBillingStore store;
    private final TenantPolicyService tenantPolicyService;
    private final ConcurrentMap<String, AtomicLong> balancesMicros = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, VirtualBillingCharge> chargesByTrace = new ConcurrentHashMap<>();

    public VirtualBillingService(LingShuProperties properties) {
        this(properties, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VirtualBillingService(
            LingShuProperties properties,
            @Nullable VirtualBillingStore store,
            @Nullable TenantPolicyService tenantPolicyService
    ) {
        this.properties = properties.getBilling().getVirtual();
        this.store = store;
        this.tenantPolicyService = tenantPolicyService;
        validateProperties();
    }

    public VirtualBillingCharge charge(
            String tenantId,
            ProviderResponse response,
            CacheStatus cacheStatus
    ) {
        return charge(tenantId, null, response, cacheStatus);
    }

    public VirtualBillingCharge charge(
            String tenantId,
            String traceId,
            ProviderResponse response,
            CacheStatus cacheStatus
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }

        VirtualBillingCharge persisted = findPersisted(tenantId, traceId);
        if (persisted != null) {
            return persisted;
        }
        BigDecimal cost = properties.isEnabled() && cacheStatus == CacheStatus.MISS
                ? costCny(tenantId, response.inputTokens(), response.outputTokens())
                : BigDecimal.ZERO;
        return applyCharge(tenantId, traceId, cost, response.inputTokens(), response.outputTokens(),
                response.provider(), response.model(), cacheStatus, "SUCCESS");
    }

    public VirtualBillingCharge recordFailure(
            String tenantId,
            String traceId,
            int inputTokens,
            int outputTokens
    ) {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        VirtualBillingCharge persisted = findPersisted(tenantId, traceId);
        if (persisted != null) {
            return persisted;
        }
        BigDecimal cost = properties.isEnabled()
                ? costCny(tenantId, inputTokens, outputTokens)
                : BigDecimal.ZERO;
        return applyCharge(tenantId, traceId, cost, inputTokens, outputTokens,
                null, null, CacheStatus.MISS, "FAILED");
    }

    public VirtualBillingCharge chargeForTrace(String traceId) {
        return traceId == null ? null : chargesByTrace.get(traceId);
    }

    public BigDecimal balance(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (store != null) {
            return store.getOrCreateBalance(tenantId, properties.getInitialBalanceCny());
        }
        AtomicLong balance = balancesMicros.computeIfAbsent(
                tenantId,
                ignored -> new AtomicLong(toMicros(properties.getInitialBalanceCny()))
        );
        return fromMicros(balance.get());
    }

    public VirtualBillingSummary summary(String tenantId) {
        BigDecimal fallback = balance(tenantId);
        return store == null
                ? new VirtualBillingSummary(tenantId, fallback, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO)
                : store.summary(tenantId, fallback);
    }

    public List<VirtualBillingRecord> recent(String tenantId, int limit) {
        return store == null ? List.of() : store.recent(tenantId, limit);
    }

    private BigDecimal costCny(String tenantId, int inputTokens, int outputTokens) {
        TenantPolicy policy = tenantPolicyService == null ? null : tenantPolicyService.resolve(tenantId);
        BigDecimal inputPrice = policy == null
                ? properties.getInputPriceUsdPerMillion() : policy.inputPriceUsdPerMillion();
        BigDecimal outputPrice = policy == null
                ? properties.getOutputPriceUsdPerMillion() : policy.outputPriceUsdPerMillion();
        BigDecimal inputUsd = inputPrice
                .multiply(BigDecimal.valueOf(inputTokens))
                .divide(MILLION, 12, RoundingMode.HALF_UP);
        BigDecimal outputUsd = outputPrice
                .multiply(BigDecimal.valueOf(outputTokens))
                .divide(MILLION, 12, RoundingMode.HALF_UP);
        return inputUsd.add(outputUsd)
                .multiply(properties.getUsdToCny())
                .setScale(12, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private VirtualBillingCharge applyCharge(
            String tenantId,
            String traceId,
            BigDecimal cost,
            int inputTokens,
            int outputTokens,
            String provider,
            String model,
            CacheStatus cacheStatus,
            String status
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (store != null && traceId != null && !traceId.isBlank()) {
            VirtualBillingRecord persisted = store.recordAtomically(
                    new VirtualBillingRecord(traceId, tenantId, provider, model, cacheStatus, status,
                            inputTokens, outputTokens, cost, BigDecimal.ZERO, Instant.now()),
                    properties.getInitialBalanceCny()
            );
            VirtualBillingCharge charge = toCharge(persisted);
            chargesByTrace.putIfAbsent(traceId, charge);
            return charge;
        }

        long costMicros = toMicros(cost);
        AtomicLong balance = balancesMicros.computeIfAbsent(
                tenantId,
                ignored -> new AtomicLong(toMicros(properties.getInitialBalanceCny()))
        );
        long remainingMicros = balance.addAndGet(-costMicros);
        VirtualBillingCharge charge = new VirtualBillingCharge(
                cost,
                fromMicros(remainingMicros),
                inputTokens,
                outputTokens
        );
        if (traceId != null && !traceId.isBlank()) {
            chargesByTrace.put(traceId, charge);
        }
        return charge;
    }

    private VirtualBillingCharge findPersisted(String tenantId, String traceId) {
        if (traceId == null || traceId.isBlank() || store == null) return null;
        try {
            Optional<VirtualBillingRecord> record = store.findByTraceId(traceId);
            if (record.isEmpty()) return null;
            VirtualBillingRecord value = record.get();
            if (!value.tenantId().equals(tenantId)) {
                throw new IllegalArgumentException("traceId is already used by another tenant");
            }
            VirtualBillingCharge charge = toCharge(value);
            chargesByTrace.putIfAbsent(traceId, charge);
            return charge;
        } catch (RuntimeException exception) {
            LOGGER.warn("Cannot load persisted virtual billing traceId={}", traceId, exception);
            return chargesByTrace.get(traceId);
        }
    }

    private VirtualBillingCharge toCharge(VirtualBillingRecord record) {
        return new VirtualBillingCharge(record.costCny(), record.remainingBalanceCny(),
                record.inputTokens(), record.outputTokens());
    }

    private long toMicros(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(NANOS_PER_CNY))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private BigDecimal fromMicros(long micros) {
        return BigDecimal.valueOf(micros)
                .divide(BigDecimal.valueOf(NANOS_PER_CNY), 9, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private void validateProperties() {
        requireNonNegative(properties.getInitialBalanceCny(), "initial balance");
        requireNonNegative(properties.getInputPriceUsdPerMillion(), "input price");
        requireNonNegative(properties.getOutputPriceUsdPerMillion(), "output price");
        if (properties.getUsdToCny() == null || properties.getUsdToCny().signum() <= 0) {
            throw new IllegalArgumentException("virtual billing USD/CNY rate must be positive");
        }
    }

    private void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("virtual billing " + name + " must not be negative");
        }
    }
}
