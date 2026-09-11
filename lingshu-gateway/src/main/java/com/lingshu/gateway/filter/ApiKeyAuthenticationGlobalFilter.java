package com.lingshu.gateway.filter;

import com.lingshu.common.contracts.ErrorCode;
import com.lingshu.gateway.config.ApiKeyProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyProperties properties;
    private final GatewayErrorResponseWriter errorWriter;

    public ApiKeyAuthenticationGlobalFilter(
            ApiKeyProperties properties,
            GatewayErrorResponseWriter errorWriter
    ) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String suppliedKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (matches(suppliedKey, properties.getValue())) {
            return chain.filter(exchange);
        }

        return errorWriter.write(
                exchange,
                HttpStatus.UNAUTHORIZED,
                ErrorCode.AUTHENTICATION_FAILED,
                "Invalid or missing API key"
        );
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private boolean matches(String suppliedKey, String configuredKey) {
        if (suppliedKey == null || configuredKey == null || configuredKey.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                suppliedKey.getBytes(StandardCharsets.UTF_8),
                configuredKey.getBytes(StandardCharsets.UTF_8)
        );
    }
}
