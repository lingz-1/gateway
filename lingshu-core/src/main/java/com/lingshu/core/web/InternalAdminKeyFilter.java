package com.lingshu.core.web;

import com.lingshu.core.config.LingShuProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@ConditionalOnProperty(prefix = "lingshu.security", name = "internal-admin-key-enabled", havingValue = "true")
public class InternalAdminKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-LingShu-Admin-Key";

    private final byte[] expectedKey;

    public InternalAdminKeyFilter(LingShuProperties properties) {
        String key = properties.getSecurity().getInternalAdminKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("LINGSHU_INTERNAL_ADMIN_KEY must be configured when internal authentication is enabled");
        }
        this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        boolean authenticated = supplied != null && MessageDigest.isEqual(
                expectedKey,
                supplied.getBytes(StandardCharsets.UTF_8)
        );
        if (!authenticated) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"AUTHENTICATION_FAILED\",\"message\":\"Invalid internal admin key\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
