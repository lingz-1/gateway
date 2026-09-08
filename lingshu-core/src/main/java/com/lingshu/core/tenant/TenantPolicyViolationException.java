package com.lingshu.core.tenant;

public class TenantPolicyViolationException extends RuntimeException {

    private final boolean rateLimited;

    public TenantPolicyViolationException(String message) {
        this(message, false);
    }

    public TenantPolicyViolationException(String message, boolean rateLimited) {
        super(message);
        this.rateLimited = rateLimited;
    }

    public boolean rateLimited() {
        return rateLimited;
    }
}
