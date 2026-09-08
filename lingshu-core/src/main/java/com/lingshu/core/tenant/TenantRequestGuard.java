package com.lingshu.core.tenant;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TenantRequestGuard {

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public Permit acquire(TenantPolicy policy) {
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
        return new Permit(window);
    }

    public static final class Permit implements AutoCloseable {

        private final Window window;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Window window) {
            this.window = window;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (window) {
                    window.inFlight--;
                }
            }
        }
    }

    private static final class Window {
        private long minute = -1;
        private int requests;
        private int inFlight;
    }
}
