package com.lingshu.common.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record ApiError(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        BigDecimal virtualCostCny,
        BigDecimal virtualRemainingBalanceCny
) {
    public ApiError {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    public static ApiError of(ErrorCode code, String message, String traceId) {
        return new ApiError(code.name(), message, traceId, Instant.now(), null, null);
    }

    public ApiError withVirtualBilling(BigDecimal costCny, BigDecimal remainingBalanceCny) {
        return new ApiError(
                code,
                message,
                traceId,
                timestamp,
                costCny,
                remainingBalanceCny
        );
    }
}
