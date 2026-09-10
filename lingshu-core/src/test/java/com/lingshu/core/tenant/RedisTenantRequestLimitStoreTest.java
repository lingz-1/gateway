package com.lingshu.core.tenant;

import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisTenantRequestLimitStoreTest {

    @Test
    void mapsRedisLimitResultsToRateLimitErrors() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisTenantRequestLimitStore store = store(redisTemplate);
        when(execute(redisTemplate)).thenReturn(-1L, -2L);

        TenantPolicyViolationException rate = assertThrows(
                TenantPolicyViolationException.class,
                () -> store.acquire(policy(1, 1))
        );
        TenantPolicyViolationException concurrent = assertThrows(
                TenantPolicyViolationException.class,
                () -> store.acquire(policy(1, 1))
        );

        assertTrue(rate.rateLimited());
        assertEquals("Tenant request rate limit exceeded", rate.getMessage());
        assertTrue(concurrent.rateLimited());
        assertEquals("Tenant concurrent request limit exceeded", concurrent.getMessage());
    }

    @Test
    void releasesRedisLeaseOnlyOnceThroughGuardPermit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(execute(redisTemplate)).thenReturn(1L);
        TenantRequestGuard guard = new TenantRequestGuard(store(redisTemplate));

        TenantRequestGuard.Permit permit = guard.acquire(policy(10, 1));
        permit.close();
        permit.close();

        verify(redisTemplate, times(2)).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(Object[].class)
        );
    }

    @Test
    void skipsRedisForUnlimitedTenant() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        assertDoesNotThrow(() -> store(redisTemplate).acquire(policy(0, 0)).release());

        verifyNoInteractions(redisTemplate);
    }

    private Long execute(StringRedisTemplate redisTemplate) {
        return redisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(Object[].class)
        );
    }

    private RedisTenantRequestLimitStore store(StringRedisTemplate redisTemplate) {
        LingShuProperties properties = new LingShuProperties();
        properties.getTenantPolicy().getRateLimit().setRedisKeyPrefix("test:rate-limit:");
        return new RedisTenantRequestLimitStore(redisTemplate, properties);
    }

    private TenantPolicy policy(int requestsPerMinute, int maxConcurrentRequests) {
        return new TenantPolicy(
                "tenant-a",
                true,
                Set.of(),
                true,
                true,
                true,
                requestsPerMinute,
                maxConcurrentRequests,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now()
        );
    }
}
