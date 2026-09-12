package com.lingshu.gateway.filter;

import com.lingshu.common.contracts.TraceHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class GatewayAccessLogGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayAccessLogGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        long startedAt = System.nanoTime();
        AtomicReference<String> errorType = new AtomicReference<>();
        return chain.filter(exchange)
                .doOnError(exception -> errorType.set(exception.getClass().getSimpleName()))
                .doFinally(signalType -> {
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    int statusCode = status == null ? (errorType.get() == null ? 200 : 500) : status.value();
                    var event = LOGGER.atInfo()
                            .addKeyValue("traceId", header(exchange, TraceHeaders.TRACE_ID, "unknown"))
                            .addKeyValue("tenantId", header(exchange, TraceHeaders.TENANT_ID, "anonymous"))
                            .addKeyValue("method", exchange.getRequest().getMethod().name())
                            .addKeyValue("path", exchange.getRequest().getPath().value())
                            .addKeyValue("status", statusCode)
                            .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                            .addKeyValue("signal", signalType.name());
                    if (errorType.get() != null) {
                        event.addKeyValue("errorType", errorType.get());
                    }
                    event.log("Gateway request completed");
                });
    }

    @Override
    public int getOrder() {
        return -190;
    }

    private String header(ServerWebExchange exchange, String name, String fallback) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
