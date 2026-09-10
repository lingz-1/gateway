package com.lingshu.core.tenant;

import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_REDIS_TESTS", matches = "true")
class RedisTenantRequestLimitStoreIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private String keyPrefix;

    @BeforeAll
    static void connect() {
        String host = System.getenv().getOrDefault("LINGSHU_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("LINGSHU_REDIS_PORT", "63790"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        String password = System.getenv().getOrDefault("LINGSHU_REDIS_PASSWORD", "");
        if (!password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @BeforeEach
    void createKeyPrefix() {
        keyPrefix = "test:lingshu-rate-limit:" + UUID.randomUUID() + ":";
    }

    @AfterEach
    void cleanKeys() {
        Set<String> keys = redisTemplate.keys(keyPrefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void sharesConcurrentAndRateLimitsAcrossInstances() {
        RedisTenantRequestLimitStore firstStore = store(Duration.ofMinutes(1));
        RedisTenantRequestLimitStore secondStore = store(Duration.ofMinutes(1));
        TenantPolicy policy = policy(2, 1);

        TenantRequestLimitStore.Lease first = firstStore.acquire(policy);
        TenantPolicyViolationException concurrent = assertThrows(
                TenantPolicyViolationException.class,
                () -> secondStore.acquire(policy)
        );
        assertEquals("Tenant concurrent request limit exceeded", concurrent.getMessage());

        first.release();
        assertDoesNotThrow(() -> secondStore.acquire(policy).release());
        TenantPolicyViolationException rate = assertThrows(
                TenantPolicyViolationException.class,
                () -> firstStore.acquire(policy)
        );
        assertEquals("Tenant request rate limit exceeded", rate.getMessage());
    }

    @Test
    void expiresAbandonedConcurrentLease() throws InterruptedException {
        RedisTenantRequestLimitStore store = store(Duration.ofMillis(50));
        TenantPolicy policy = policy(0, 1);

        store.acquire(policy);
        assertThrows(TenantPolicyViolationException.class, () -> store.acquire(policy));
        Thread.sleep(150);

        assertDoesNotThrow(() -> store.acquire(policy).release());
    }

    private RedisTenantRequestLimitStore store(Duration permitTtl) {
        LingShuProperties properties = new LingShuProperties();
        properties.getTenantPolicy().getRateLimit().setRedisKeyPrefix(keyPrefix);
        properties.getTenantPolicy().getRateLimit().setPermitTtl(permitTtl);
        return new RedisTenantRequestLimitStore(redisTemplate, properties);
    }

    private TenantPolicy policy(int requestsPerMinute, int maxConcurrentRequests) {
        return new TenantPolicy(
                "tenant-shared",
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
