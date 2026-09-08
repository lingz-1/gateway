package com.lingshu.gateway.filter;

import com.lingshu.common.contracts.TraceHeaders;
import com.lingshu.common.contracts.TraceIdSupport;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceIdSupport.resolve(exchange.getRequest().getHeaders().getFirst(TraceHeaders.TRACE_ID));
        ServerWebExchange tracedExchange = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(TraceHeaders.TRACE_ID, traceId)))
                .build();
        tracedExchange.getResponse().beforeCommit(() -> {
            tracedExchange.getResponse().getHeaders().set(TraceHeaders.TRACE_ID, traceId);
            return Mono.empty();
        });
        return chain.filter(tracedExchange);
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
