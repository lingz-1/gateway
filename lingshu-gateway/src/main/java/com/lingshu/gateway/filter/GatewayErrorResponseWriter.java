package com.lingshu.gateway.filter;

import com.lingshu.common.contracts.ApiError;
import com.lingshu.common.contracts.ErrorCode;
import com.lingshu.common.contracts.TraceHeaders;
import com.lingshu.common.contracts.TraceIdSupport;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class GatewayErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public GatewayErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(
            ServerWebExchange exchange,
            HttpStatus status,
            ErrorCode code,
            String message
    ) {
        if (exchange.getResponse().isCommitted()) {
            return exchange.getResponse().setComplete();
        }
        String traceId = TraceIdSupport.resolve(
                exchange.getRequest().getHeaders().getFirst(TraceHeaders.TRACE_ID)
        );
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ApiError.of(code, message, traceId));
        } catch (Exception exception) {
            return Mono.error(new IllegalStateException("Unable to serialize gateway error", exception));
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
        exchange.getResponse().getHeaders().set(TraceHeaders.TRACE_ID, traceId);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
