package com.lingshu.core.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.tenant-policy.rate-limit",
        name = "store",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryTenantRequestLimitStore implements TenantRequestLimitStore {

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public Lease acquire(TenantPolicy policy) {
        Window window = windows.computeIfAbsent(policy.tenantId(), ignored -> new Window());
        synchronized (window) {
            long minute = Instant.now().getEpochSecond() / 60;
            if (window.minute != minute) {
                window.minute = minute;
                window.requests = 0;
            }
            if (policy.requestsPerMinute() > 0 && window.requests >= policy.requestsPerMinute()) {
                throw new TenantPolicyViolationException("Tenant request rate limit exceeded", true);
            }
            if (policy.maxConcurrentRequests() > 0 && window.inFlight >= policy.maxConcurrentRequests()) {
                throw new TenantPolicyViolationException("Tenant concurrent request limit exceeded", true);
            }
            window.requests++;
            window.inFlight++;
        }
        return () -> release(window);
    }

    private void release(Window window) {
        synchronized (window) {
            window.inFlight = Math.max(0, window.inFlight - 1);
        }
    }

    private static final class Window {

        private long minute = -1;
        private int requests;
        private int inFlight;
    }
}
