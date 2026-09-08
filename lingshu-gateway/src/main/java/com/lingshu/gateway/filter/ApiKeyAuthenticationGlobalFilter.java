package com.lingshu.gateway.filter;

import com.lingshu.common.contracts.ErrorCode;
import com.lingshu.common.contracts.TraceHeaders;
import com.lingshu.gateway.config.ApiKeyProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

@Component
public class ApiKeyAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyProperties properties;

    public ApiKeyAuthenticationGlobalFilter(ApiKeyProperties properties) {
        this.properties = properties;
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

        String traceId = exchange.getRequest().getHeaders().getFirst(TraceHeaders.TRACE_ID);
        byte[] body = unauthorizedBody(traceId).getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
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

    private String unauthorizedBody(String traceId) {
        return "{\"code\":\"" + ErrorCode.AUTHENTICATION_FAILED.name()
                + "\",\"message\":\"Invalid or missing API key\",\"traceId\":\""
                + traceId
                + "\",\"timestamp\":\""
                + Instant.now()
                + "\"}";
    }
}
