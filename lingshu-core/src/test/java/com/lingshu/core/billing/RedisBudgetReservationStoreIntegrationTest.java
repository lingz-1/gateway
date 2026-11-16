package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_REDIS_TESTS", matches = "true")
class RedisBudgetReservationStoreIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private String keyPrefix;
    private RedisBudgetReservationStore store;

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
    void createStore() {
        keyPrefix = "test:lingshu-budget:" + UUID.randomUUID() + ":";
        LingShuProperties properties = new LingShuProperties();
        properties.getBilling().setRedisKeyPrefix(keyPrefix);
        properties.getBilling().setReservationTtl(Duration.ofMinutes(5));
        store = new RedisBudgetReservationStore(redisTemplate, properties);
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

    @RepeatedTest(value = 10, name = "budget-concurrency-{currentRepetition}")
    void concurrentReservationsNeverOverspendAndSettlementsRemainIdempotent() throws Exception {
        String tenantId = "tenant-concurrent";
        redisTemplate.opsForValue().set(store.balanceKey(tenantId), "1000");
        List<String> reservationIds = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            reservationIds.add("reservation-" + index);
        }

        List<Boolean> reserveResults = runConcurrent(200, index ->
                store.reserve(tenantId, reservationIds.get(index), 10));
        List<String> accepted = new ArrayList<>();
        for (int index = 0; index < reserveResults.size(); index++) {
            if (reserveResults.get(index)) {
                accepted.add(reservationIds.get(index));
            }
        }

        assertEquals(100, accepted.size());
        assertEquals("0", redisTemplate.opsForValue().get(store.balanceKey(tenantId)));
        List<String> confirmed = accepted.subList(0, 50);
        List<String> released = accepted.subList(50, 100);
        assertTrue(runConcurrent(100, index ->
                store.confirm(confirmed.get(index % confirmed.size()))).stream().allMatch(Boolean::booleanValue));
        assertTrue(runConcurrent(100, index ->
                store.release(released.get(index % released.size()))).stream().allMatch(Boolean::booleanValue));

        assertEquals("500", redisTemplate.opsForValue().get(store.balanceKey(tenantId)));
        assertFalse(store.release(confirmed.getFirst()));
        assertEquals("500", redisTemplate.opsForValue().get(store.balanceKey(tenantId)));
    }

    @RepeatedTest(value = 10, name = "budget-duplicate-{currentRepetition}")
    void concurrentDuplicateReservationDebitsAndReleasesOnlyOnce() throws Exception {
        String tenantId = "tenant-duplicate";
        String reservationId = "reservation-shared";
        redisTemplate.opsForValue().set(store.balanceKey(tenantId), "100");

        assertTrue(runConcurrent(64, ignored ->
                store.reserve(tenantId, reservationId, 10)).stream().allMatch(Boolean::booleanValue));
        assertEquals("90", redisTemplate.opsForValue().get(store.balanceKey(tenantId)));
        assertTrue(runConcurrent(64, ignored ->
                store.release(reservationId)).stream().allMatch(Boolean::booleanValue));
        assertEquals("100", redisTemplate.opsForValue().get(store.balanceKey(tenantId)));
    }

    @Test
    void rejectsConflictingReplayOfReservationIdentity() {
        redisTemplate.opsForValue().set(store.balanceKey("tenant-a"), "100");
        redisTemplate.opsForValue().set(store.balanceKey("tenant-b"), "100");

        assertTrue(store.reserve("tenant-a", "reservation-conflict", 10));
        assertFalse(store.reserve("tenant-b", "reservation-conflict", 10));
        assertFalse(store.reserve("tenant-a", "reservation-conflict", 20));

        assertEquals("90", redisTemplate.opsForValue().get(store.balanceKey("tenant-a")));
        assertEquals("100", redisTemplate.opsForValue().get(store.balanceKey("tenant-b")));
    }

    @RepeatedTest(value = 10, name = "budget-delta-settlement-{currentRepetition}")
    void concurrentDeltaSettlementAdjustsBalanceOnceAndPreservesUsageSource() throws Exception {
        String tenantId = "tenant-delta";
        String reservationId = "reservation-delta";
        redisTemplate.opsForValue().set(store.balanceKey(tenantId), "1000");
        assertTrue(store.reserve(tenantId, reservationId, 100));

        assertTrue(runConcurrent(64, ignored -> store.settle(
                reservationId, 60, TokenUsageSource.PROVIDER_REPORTED
        )).stream().allMatch(Boolean::booleanValue));

        assertEquals("940", redisTemplate.opsForValue().get(store.balanceKey(tenantId)));
        assertEquals("60", redisTemplate.opsForHash().get(
                store.reservationKey(reservationId), "actual_units"
        ));
        assertEquals("PROVIDER_REPORTED", redisTemplate.opsForHash().get(
                store.reservationKey(reservationId), "usage_source"
        ));
        assertFalse(store.settle(reservationId, 61, TokenUsageSource.PROVIDER_REPORTED));
        assertEquals("940", redisTemplate.opsForValue().get(store.balanceKey(tenantId)));
    }

    @Test
    void overageSettlementDebitsDifferenceAndCanCreateDebt() {
        String tenantId = "tenant-overage";
        String reservationId = "reservation-overage";
        redisTemplate.opsForValue().set(store.balanceKey(tenantId), "100");
        assertTrue(store.reserve(tenantId, reservationId, 80));

        assertTrue(store.settle(reservationId, 130, TokenUsageSource.ESTIMATED));

        assertEquals("-30", redisTemplate.opsForValue().get(store.balanceKey(tenantId)));
        assertEquals("-50", redisTemplate.opsForHash().get(
                store.reservationKey(reservationId), "released_units"
        ));
    }

    private List<Boolean> runConcurrent(int tasks, IntFunction<Boolean> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(tasks, 32));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < tasks; index++) {
                int taskIndex = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.apply(taskIndex);
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
