package com.lingshu.core.billing;

import com.lingshu.common.dto.CacheStatus;
import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.common.dto.TokenUsageSource;
import com.lingshu.core.config.LingShuProperties;
import com.lingshu.core.provider.ProviderRoutingException;
import com.lingshu.core.provider.ProviderUsageException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "lingshu.billing", name = "enabled", havingValue = "true")
public class BudgetRequestCoordinator {

    private static final int CONVERGENCE_ATTEMPTS = 3;

    private final BudgetReservationService reservationService;
    private final int defaultOutputUnits;

    public BudgetRequestCoordinator(
            BudgetReservationService reservationService,
            LingShuProperties properties
    ) {
        this.reservationService = reservationService;
        this.defaultOutputUnits = properties.getBilling().getReservationDefaultOutputUnits();
        if (defaultOutputUnits <= 0) {
            throw new IllegalArgumentException("Default budget output units must be positive");
        }
    }

    public BudgetReservation reserve(
            ChatCompletionRequest request,
            String traceId,
            String tenantId
    ) {
        long inputUnits = estimateTokens(request.prompt());
        long outputUnits = request.max_tokens() == null
                ? defaultOutputUnits
                : request.max_tokens();
        return reservationService.reserve(tenantId, traceId, Math.addExact(inputUnits, outputUnits));
    }

    public BudgetSettlement settle(
            BudgetReservation reservation,
            ChatCompletionRequest request,
            ProviderResponse response,
            CacheStatus cacheStatus
    ) {
        if (cacheStatus != CacheStatus.MISS) {
            release(reservation);
            return null;
        }
        Usage usage = usage(request, response);
        return settleWithRetry(reservation.reservationId(), usage.units(), usage.source());
    }

    public void fail(BudgetReservation reservation, RuntimeException failure) {
        Usage usage = failureUsage(failure);
        if (usage.units() == 0) {
            release(reservation);
            return;
        }
        settleWithRetry(reservation.reservationId(), usage.units(), usage.source());
    }

    public void release(BudgetReservation reservation) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= CONVERGENCE_ATTEMPTS; attempt++) {
            try {
                reservationService.release(reservation.reservationId());
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    private BudgetSettlement settleWithRetry(
            String reservationId,
            long actualUnits,
            TokenUsageSource usageSource
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= CONVERGENCE_ATTEMPTS; attempt++) {
            try {
                return reservationService.settle(reservationId, actualUnits, usageSource);
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    private Usage usage(ChatCompletionRequest request, ProviderResponse response) {
        if (response.usageSource() != TokenUsageSource.UNKNOWN) {
            return new Usage(
                    (long) response.inputTokens() + response.outputTokens(),
                    response.usageSource()
            );
        }
        long inputUnits = response.inputTokens() > 0
                ? response.inputTokens()
                : estimateTokens(request.prompt());
        long outputUnits = response.outputTokens() > 0
                ? response.outputTokens()
                : estimateTokens(response.content());
        return new Usage(inputUnits + outputUnits, TokenUsageSource.ESTIMATED);
    }

    private Usage failureUsage(RuntimeException failure) {
        if (failure instanceof ProviderUsageException usageException) {
            return new Usage(
                    (long) usageException.inputTokens() + usageException.outputTokens(),
                    TokenUsageSource.MIXED
            );
        }
        if (failure instanceof ProviderRoutingException routingException) {
            return new Usage(
                    (long) routingException.inputTokens() + routingException.outputTokens(),
                    TokenUsageSource.MIXED
            );
        }
        return new Usage(0, TokenUsageSource.ESTIMATED);
    }

    private long estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (text.codePointCount(0, text.length()) + 3L) / 4L);
    }

    private record Usage(long units, TokenUsageSource source) {
    }
}
