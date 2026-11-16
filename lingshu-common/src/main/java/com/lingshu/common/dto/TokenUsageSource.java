package com.lingshu.common.dto;

public enum TokenUsageSource {
    PROVIDER_REPORTED,
    ESTIMATED,
    MIXED,
    UNKNOWN;

    public boolean includesEstimate() {
        return this == ESTIMATED || this == MIXED;
    }
}
