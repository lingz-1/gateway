package com.lingshu.core.tenant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class TenantRequestGuard {

    private final TenantRequestLimitStore store;

    @Autowired
    public TenantRequestGuard(TenantRequestLimitStore store) {
        this.store = store;
    }

    public TenantRequestGuard() {
        this(new InMemoryTenantRequestLimitStore());
    }

    public Permit acquire(TenantPolicy policy) {
        return new Permit(store.acquire(policy));
    }

    public static final class Permit implements AutoCloseable {

        private final TenantRequestLimitStore.Lease lease;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(TenantRequestLimitStore.Lease lease) {
            this.lease = lease;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                lease.release();
            }
        }
    }
}
