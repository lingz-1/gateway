package com.lingshu.core.billing;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.billing",
        name = "enabled",
        havingValue = "true"
)
public class RedisBudgetReservationStore implements BudgetReservationStore {

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = script("""
            local status = redis.call('HGET', KEYS[2], 'status')
            if status then
              if status == 'PENDING' then return 2 else return 0 end
            end
            local balance = redis.call('GET', KEYS[1])
            if not balance then return -2 end
            local current = tonumber(balance)
            local units = tonumber(ARGV[1])
            if not current or current < units then return -1 end
            redis.call('DECRBY', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[2], 'tenant_id', ARGV[2], 'units', ARGV[1], 'status', 'PENDING')
            redis.call('EXPIRE', KEYS[2], ARGV[3])
            return 1
            """);

    private static final DefaultRedisScript<Long> CONFIRM_SCRIPT = script("""
            local status = redis.call('HGET', KEYS[1], 'status')
            if status == 'PENDING' then
              redis.call('HSET', KEYS[1], 'status', 'CONFIRMED')
              return 1
            end
            if status == 'CONFIRMED' then return 2 end
            return 0
            """);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script("""
            local status = redis.call('HGET', KEYS[1], 'status')
            if status == 'PENDING' then
              local units = redis.call('HGET', KEYS[1], 'units')
              local tenant = redis.call('HGET', KEYS[1], 'tenant_id')
              if not units or not tenant then return 0 end
              redis.call('INCRBY', KEYS[2] .. tenant, units)
              redis.call('HSET', KEYS[1], 'status', 'RELEASED')
              return 1
            end
            if status == 'RELEASED' then return 2 end
            return 0
            """);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long reservationTtlSeconds;

    @Autowired
    public RedisBudgetReservationStore(
            StringRedisTemplate redisTemplate,
            LingShuProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        LingShuProperties.Billing billing = properties.getBilling();
        this.keyPrefix = billing.getRedisKeyPrefix();
        Duration ttl = billing.getReservationTtl();
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("Billing Redis key prefix must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Billing reservation TTL must be positive");
        }
        this.reservationTtlSeconds = Math.max(1, ttl.toSeconds());
    }

    @Override
    public boolean reserve(String tenantId, String reservationId, long units) {
        validate(tenantId, reservationId, units);
        Long result = redisTemplate.execute(
                RESERVE_SCRIPT,
                List.of(balanceKey(tenantId), reservationKey(reservationId)),
                Long.toString(units), tenantId, Long.toString(reservationTtlSeconds)
        );
        return result != null && (result == 1 || result == 2);
    }

    @Override
    public boolean confirm(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        Long result = redisTemplate.execute(
                CONFIRM_SCRIPT,
                List.of(reservationKey(reservationId))
        );
        return result != null && (result == 1 || result == 2);
    }

    @Override
    public boolean release(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        Long result = redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(reservationKey(reservationId), balancePrefix())
        );
        return result != null && (result == 1 || result == 2);
    }

    String balanceKey(String tenantId) {
        return balancePrefix() + tenantId;
    }

    String reservationKey(String reservationId) {
        return keyPrefix + "reservation:" + reservationId;
    }

    private String balancePrefix() {
        return keyPrefix + "balance:";
    }

    private void validate(String tenantId, String reservationId, long units) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        if (units <= 0) {
            throw new IllegalArgumentException("reservation units must be positive");
        }
    }

    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }
}
