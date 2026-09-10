package com.lingshu.core.tenant;

import com.lingshu.core.config.LingShuProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.tenant-policy.rate-limit",
        name = "store",
        havingValue = "redis"
)
public class RedisTenantRequestLimitStore implements TenantRequestLimitStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisTenantRequestLimitStore.class);
    private static final long RATE_WINDOW_TTL_MILLIS = 120_000;
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = script("""
            local redis_time = redis.call('TIME')
            local now_seconds = tonumber(redis_time[1])
            local now_millis = now_seconds * 1000 + math.floor(tonumber(redis_time[2]) / 1000)
            local current_minute = math.floor(now_seconds / 60)
            local rpm_limit = tonumber(ARGV[1])
            local concurrency_limit = tonumber(ARGV[2])
            local lease_ttl_millis = tonumber(ARGV[4])

            local requests = 0
            if rpm_limit > 0 then
              local stored_minute = tonumber(redis.call('HGET', KEYS[1], 'minute') or '-1')
              if stored_minute == current_minute then
                requests = tonumber(redis.call('HGET', KEYS[1], 'requests') or '0')
              end
              if requests >= rpm_limit then return -1 end
            end

            if concurrency_limit > 0 then
              redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now_millis)
              if redis.call('ZCARD', KEYS[2]) >= concurrency_limit then return -2 end
            end

            if rpm_limit > 0 then
              redis.call('HSET', KEYS[1], 'minute', current_minute, 'requests', requests + 1)
              redis.call('PEXPIRE', KEYS[1], ARGV[3])
            end
            if concurrency_limit > 0 then
              redis.call('ZADD', KEYS[2], now_millis + lease_ttl_millis, ARGV[5])
              redis.call('PEXPIRE', KEYS[2], lease_ttl_millis * 2)
            end
            return 1
            """);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script("""
            local removed = redis.call('ZREM', KEYS[1], ARGV[1])
            if redis.call('ZCARD', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end
            return removed
            """);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long permitTtlMillis;

    public RedisTenantRequestLimitStore(StringRedisTemplate redisTemplate, LingShuProperties properties) {
        this.redisTemplate = redisTemplate;
        LingShuProperties.RateLimit rateLimit = properties.getTenantPolicy().getRateLimit();
        this.keyPrefix = rateLimit.getRedisKeyPrefix();
        Duration permitTtl = rateLimit.getPermitTtl();
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("Rate limit Redis key prefix must not be blank");
        }
        if (permitTtl == null || permitTtl.isZero() || permitTtl.isNegative()) {
            throw new IllegalArgumentException("Rate limit permit TTL must be positive");
        }
        this.permitTtlMillis = Math.max(1, permitTtl.toMillis());
    }

    @Override
    public Lease acquire(TenantPolicy policy) {
        if (policy.requestsPerMinute() <= 0 && policy.maxConcurrentRequests() <= 0) {
            return () -> { };
        }
        String tenantKey = tenantKey(policy.tenantId());
        String leaseId = UUID.randomUUID().toString();
        Long result = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(windowKey(tenantKey), leaseKey(tenantKey)),
                Integer.toString(policy.requestsPerMinute()),
                Integer.toString(policy.maxConcurrentRequests()),
                Long.toString(RATE_WINDOW_TTL_MILLIS),
                Long.toString(permitTtlMillis),
                leaseId
        );
        if (result == null) {
            throw new IllegalStateException("Redis rate limiter returned no result");
        }
        if (result == -1) {
            throw new TenantPolicyViolationException("Tenant request rate limit exceeded", true);
        }
        if (result == -2) {
            throw new TenantPolicyViolationException("Tenant concurrent request limit exceeded", true);
        }
        if (result != 1) {
            throw new IllegalStateException("Redis rate limiter returned an unexpected result: " + result);
        }
        if (policy.maxConcurrentRequests() <= 0) {
            return () -> { };
        }
        return () -> release(tenantKey, leaseId);
    }

    private void release(String tenantKey, String leaseId) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(leaseKey(tenantKey)), leaseId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Cannot release Redis tenant request permit tenantKey={}", tenantKey, exception);
        }
    }

    private String windowKey(String tenantKey) {
        return keyPrefix + "{" + tenantKey + "}:window";
    }

    private String leaseKey(String tenantKey) {
        return keyPrefix + "{" + tenantKey + "}:leases";
    }

    private String tenantKey(String tenantId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tenantId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }
}
