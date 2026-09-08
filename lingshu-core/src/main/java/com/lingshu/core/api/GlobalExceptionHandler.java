package com.lingshu.core.api;

import com.lingshu.common.contracts.ApiError;
import com.lingshu.common.contracts.ErrorCode;
import com.lingshu.core.billing.VirtualBillingCharge;
import com.lingshu.core.billing.VirtualBillingService;
import com.lingshu.core.provider.ProviderRoutingException;
import com.lingshu.core.tenant.TenantPolicyViolationException;
import com.lingshu.core.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final VirtualBillingService virtualBillingService;

    public GlobalExceptionHandler(VirtualBillingService virtualBillingService) {
        this.virtualBillingService = virtualBillingService;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Request validation failed");
        return error(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, message, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiError> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "Request body is invalid", request);
    }

    @ExceptionHandler(ProviderRoutingException.class)
    public ResponseEntity<ApiError> handleRouting(
            ProviderRoutingException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.PROVIDER_UNAVAILABLE,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(TenantPolicyViolationException.class)
    public ResponseEntity<ApiError> handleTenantPolicy(
            TenantPolicyViolationException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = exception.rateLimited() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.FORBIDDEN;
        ErrorCode code = exception.rateLimited() ? ErrorCode.RATE_LIMITED : ErrorCode.INVALID_REQUEST;
        return error(status, code, exception.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        String traceId = traceId(request);
        LOGGER.error("Unhandled chat completion error traceId={}", traceId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(withBilling(ApiError.of(ErrorCode.INTERNAL_ERROR, "Internal server error", traceId), traceId));
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            ErrorCode code,
            String message,
            HttpServletRequest request
    ) {
        String traceId = traceId(request);
        return ResponseEntity.status(status).body(withBilling(ApiError.of(code, message, traceId), traceId));
    }

    private ApiError withBilling(ApiError error, String traceId) {
        VirtualBillingCharge charge = virtualBillingService.chargeForTrace(traceId);
        if (charge == null) {
            return error;
        }
        return error.withVirtualBilling(charge.costCny(), charge.remainingBalanceCny());
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
