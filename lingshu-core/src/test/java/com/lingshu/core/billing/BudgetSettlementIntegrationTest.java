package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;
import com.lingshu.core.config.LingShuProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_REDIS_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_POSTGRES_TESTS", matches = "true")
class BudgetSettlementIntegrationTest {

    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static HikariDataSource dataSource;

    private final List<String> reservationIds = new ArrayList<>();
    private String keyPrefix;
    private RedisBudgetReservationStore redisStore;
    private BudgetReservationService service;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("LINGSHU_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("LINGSHU_REDIS_PORT", "63790"))
        );
        String redisPassword = System.getenv().getOrDefault("LINGSHU_REDIS_PASSWORD", "");
        if (!redisPassword.isBlank()) {
            redis.setPassword(RedisPassword.of(redisPassword));
        }
        redisConnectionFactory = new LettuceConnectionFactory(redis);
        redisConnectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();

        String url = System.getenv().getOrDefault(
                "LINGSHU_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:54320/lingshu"
        );
        String username = System.getenv().getOrDefault("LINGSHU_POSTGRES_USER", "lingshu");
        String password = System.getenv("LINGSHU_POSTGRES_PASSWORD");
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setUsername(username);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(16);
        dataSource = new HikariDataSource(hikari);
    }

    @BeforeEach
    void setUp() {
        keyPrefix = "test:lingshu-settlement:" + UUID.randomUUID() + ":";
        LingShuProperties properties = new LingShuProperties();
        properties.getBilling().setRedisKeyPrefix(keyPrefix);
        redisStore = new RedisBudgetReservationStore(redisTemplate, properties);
        service = new BudgetReservationService(
                redisStore,
                new JdbcBudgetLedger(dataSource),
                properties
        );
    }

    @AfterEach
    void cleanUp() throws Exception {
        Set<String> keys = redisTemplate.keys(keyPrefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        try (Connection connection = dataSource.getConnection()) {
            for (String reservationId : reservationIds) {
                try (PreparedStatement outbox = connection.prepareStatement(
                        "DELETE FROM lingshu_budget_outbox WHERE reservation_id = ?")) {
                    outbox.setString(1, reservationId);
                    outbox.executeUpdate();
                }
                try (PreparedStatement ledger = connection.prepareStatement(
                        "DELETE FROM lingshu_budget_ledger WHERE reservation_id = ?")) {
                    ledger.setString(1, reservationId);
                    ledger.executeUpdate();
                }
            }
        }
        reservationIds.clear();
    }

    @AfterAll
    static void disconnect() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @Test
    void reservesSettlesActualUsageAndReleasesDifferenceAcrossRedisAndPostgres() throws Exception {
        String tenantId = "tenant-normal";
        String reservationId = reservationId();
        redisTemplate.opsForValue().set(redisStore.balanceKey(tenantId), "1000");

        service.reserve(tenantId, reservationId, 100);
        BudgetSettlement settlement = service.settle(
                reservationId, 60, TokenUsageSource.PROVIDER_REPORTED
        );

        assertEquals(40, settlement.releasedUnits());
        assertEquals("940", redisTemplate.opsForValue().get(redisStore.balanceKey(tenantId)));
        assertEquals("60:PROVIDER_REPORTED", ledgerValues(reservationId));
        assertEquals("60:PROVIDER_REPORTED:1", outboxValues(reservationId));
    }

    @Test
    void settlesOverageAndPreservesEstimatedUsageSource() throws Exception {
        String tenantId = "tenant-overage";
        String reservationId = reservationId();
        redisTemplate.opsForValue().set(redisStore.balanceKey(tenantId), "100");

        service.reserve(tenantId, reservationId, 80);
        BudgetSettlement settlement = service.settle(
                reservationId, 130, TokenUsageSource.ESTIMATED
        );

        assertTrue(settlement.overage());
        assertEquals(-50, settlement.releasedUnits());
        assertEquals("-30", redisTemplate.opsForValue().get(redisStore.balanceKey(tenantId)));
        assertEquals("130:ESTIMATED", ledgerValues(reservationId));
    }

    @Test
    void concurrentDuplicateSettlementChangesBalanceOnceAndCreatesOneOutboxEvent() throws Exception {
        String tenantId = "tenant-concurrent";
        String reservationId = reservationId();
        redisTemplate.opsForValue().set(redisStore.balanceKey(tenantId), "1000");
        service.reserve(tenantId, reservationId, 100);

        List<BudgetSettlement> results = runConcurrent(32, () -> service.settle(
                reservationId, 60, TokenUsageSource.MIXED
        ));

        assertEquals(32, results.size());
        assertEquals("940", redisTemplate.opsForValue().get(redisStore.balanceKey(tenantId)));
        assertEquals("60:MIXED:1", outboxValues(reservationId));
    }

    private String reservationId() {
        String reservationId = "budget-settlement-it-" + UUID.randomUUID();
        reservationIds.add(reservationId);
        return reservationId;
    }

    private String ledgerValues(String reservationId) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT actual_units, usage_source FROM lingshu_budget_ledger WHERE reservation_id = ?")) {
            statement.setString(1, reservationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) + ":" + resultSet.getString(2);
            }
        }
    }

    private String outboxValues(String reservationId) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT units, usage_source, COUNT(*) OVER () FROM lingshu_budget_outbox WHERE reservation_id = ?")) {
            statement.setString(1, reservationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) + ":" + resultSet.getString(2) + ":" + resultSet.getInt(3);
            }
        }
    }

    private List<BudgetSettlement> runConcurrent(
            int tasks,
            java.util.function.Supplier<BudgetSettlement> action
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BudgetSettlement>> futures = new ArrayList<>();
            for (int index = 0; index < tasks; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.get();
                }));
            }
            start.countDown();
            List<BudgetSettlement> results = new ArrayList<>();
            for (Future<BudgetSettlement> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
