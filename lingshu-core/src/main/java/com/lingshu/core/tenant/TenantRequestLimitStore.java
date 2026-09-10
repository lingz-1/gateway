package com.lingshu.core.tenant;

public interface TenantRequestLimitStore {

    Lease acquire(TenantPolicy policy);

    @FunctionalInterface
    interface Lease {

        void release();
    }
}
