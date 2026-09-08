package com.lingshu.core.provider;

import java.time.Instant;
import java.util.Objects;

public record ProviderHealth(
        String provider,
        Status status,
        Instant checkedAt
) {
    public ProviderHealth {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(checkedAt, "checkedAt must not be null");
    }

    public enum Status {
        UP,
        DOWN
    }
}
