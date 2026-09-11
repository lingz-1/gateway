package com.lingshu.gateway.filter;

import com.lingshu.common.contracts.ErrorCode;
import com.lingshu.common.contracts.TraceHeaders;
import com.lingshu.gateway.config.RequestPolicyProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class RequestPolicyGlobalFilter implements GlobalFilter, Ordered {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Pattern VALID_TENANT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final RequestPolicyProperties properties;
    private final GatewayErrorResponseWriter errorWriter;

    public RequestPolicyGlobalFilter(
            RequestPolicyProperties properties,
            GatewayErrorResponseWriter errorWriter
    ) {
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isChatCompletionRequest(exchange)) {
            return chain.filter(exchange);
        }

        List<String> tenantIds = exchange.getRequest().getHeaders().getOrEmpty(TraceHeaders.TENANT_ID);
        if ((properties.isTenantIdRequired() && tenantIds.isEmpty())
                || (!tenantIds.isEmpty() && !validTenantId(tenantIds))) {
            return errorWriter.write(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_REQUEST,
                    "Missing or invalid X-Tenant-Id header"
            );
        }

        MediaType contentType;
        try {
            contentType = exchange.getRequest().getHeaders().getContentType();
        } catch (IllegalArgumentException exception) {
            contentType = null;
        }
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return errorWriter.write(
                    exchange,
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Type must be application/json"
            );
        }

        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength > properties.getMaxBodyBytes()) {
            return payloadTooLarge(exchange);
        }
        return cacheAndForwardBody(exchange, chain);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private boolean isChatCompletionRequest(ServerWebExchange exchange) {
        return HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && CHAT_COMPLETIONS_PATH.equals(exchange.getRequest().getPath().value());
    }

    private boolean validTenantId(List<String> values) {
        return values.size() == 1 && VALID_TENANT_ID.matcher(values.getFirst()).matches();
    }

    private Mono<Void> cacheAndForwardBody(ServerWebExchange exchange, GatewayFilterChain chain) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), properties.getMaxBodyBytes())
                .map(this::copyAndRelease)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> chain.filter(withCachedBody(exchange, body)))
                .onErrorResume(this::causedByBodyLimit, exception -> payloadTooLarge(exchange));
    }

    private byte[] copyAndRelease(DataBuffer buffer) {
        try {
            byte[] body = new byte[buffer.readableByteCount()];
            buffer.read(body, 0, body.length);
            return body;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private ServerWebExchange withCachedBody(ServerWebExchange exchange, byte[] body) {
        ServerHttpRequestDecorator request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
            }

            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(HttpHeaders.TRANSFER_ENCODING);
                headers.setContentLength(body.length);
                return headers;
            }
        };
        return exchange.mutate().request(request).build();
    }

    private boolean causedByBodyLimit(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof DataBufferLimitException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Mono<Void> payloadTooLarge(ServerWebExchange exchange) {
        return errorWriter.write(
                exchange,
                HttpStatus.CONTENT_TOO_LARGE,
                ErrorCode.PAYLOAD_TOO_LARGE,
                "Request body exceeds the configured gateway limit"
        );
    }
}
