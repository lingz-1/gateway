package com.lingshu.core.web;

import com.lingshu.common.contracts.TraceHeaders;
import com.lingshu.common.contracts.TraceIdSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = TraceIdSupport.resolve(request.getHeader(TraceHeaders.TRACE_ID));
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TraceHeaders.TRACE_ID, traceId);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
