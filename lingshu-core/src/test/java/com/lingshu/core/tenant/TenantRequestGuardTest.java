package com.lingshu.core.tenant;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantRequestGuardTest {

    @Test
    void enforcesConcurrentLimitAndReleasesPermit() {
        TenantRequestGuard guard = new TenantRequestGuard();
        TenantPolicy policy = policy(0, 1);

        TenantRequestGuard.Permit first = guard.acquire(policy);
        assertThrows(TenantPolicyViolationException.class, () -> guard.acquire(policy));
        first.close();
        assertDoesNotThrow(() -> guard.acquire(policy).close());
    }

    @Test
    void enforcesPerMinuteLimit() {
        TenantRequestGuard guard = new TenantRequestGuard();
        TenantPolicy policy = policy(1, 0);

        guard.acquire(policy).close();
        assertThrows(TenantPolicyViolationException.class, () -> guard.acquire(policy));
    }

    private TenantPolicy policy(int perMinute, int concurrent) {
        return new TenantPolicy("tenant-a", true, Set.of(), true, true, true, perMinute, concurrent,
                BigDecimal.ZERO, BigDecimal.ZERO, Instant.now());
    }
}
