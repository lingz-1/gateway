package com.lingshu.core.billing;

import com.lingshu.common.dto.CacheStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_POSTGRES_TESTS", matches = "true")
class JdbcVirtualBillingStoreIntegrationTest {

    private static HikariDataSource dataSource;
    private static JdbcVirtualBillingStore store;
    private static String tenantId;

    @BeforeAll
    static void setUp() {
        String url = System.getenv().getOrDefault("LINGSHU_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:54320/lingshu");
        String username = System.getenv().getOrDefault("LINGSHU_POSTGRES_USER", "lingshu");
        String password = System.getenv("LINGSHU_POSTGRES_PASSWORD");
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        store = new JdbcVirtualBillingStore(dataSource);
        tenantId = "billing-it-" + UUID.randomUUID();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (dataSource == null) return;
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement usage = connection.prepareStatement(
                    "DELETE FROM lingshu_virtual_billing_usage WHERE tenant_id = ?")) {
                usage.setString(1, tenantId);
                usage.executeUpdate();
            }
            try (PreparedStatement account = connection.prepareStatement(
                    "DELETE FROM lingshu_virtual_billing_accounts WHERE tenant_id = ?")) {
                account.setString(1, tenantId);
                account.executeUpdate();
            }
        }
        dataSource.close();
    }

    @Test
    void chargesAtomicallyAcrossConcurrentRequestsAndDeduplicatesTrace() throws Exception {
        int requestCount = 20;
        BigDecimal cost = new BigDecimal("0.010000000");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<VirtualBillingRecord>> futures = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                String traceId = "trace-" + index + "-" + UUID.randomUUID();
                futures.add(executor.submit(() -> store.recordAtomically(record(traceId, cost), new BigDecimal("10.00"))));
            }
            for (Future<VirtualBillingRecord> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        String duplicateTrace = "duplicate-" + UUID.randomUUID();
        VirtualBillingRecord first = store.recordAtomically(record(duplicateTrace, cost), new BigDecimal("10.00"));
        VirtualBillingRecord duplicate = store.recordAtomically(record(duplicateTrace, cost), new BigDecimal("10.00"));

        assertEquals(first.traceId(), duplicate.traceId());
        assertEquals(first.costCny(), duplicate.costCny());
        assertEquals(first.remainingBalanceCny(), duplicate.remainingBalanceCny());
        assertEquals(new BigDecimal("9.790000000"), store.getOrCreateBalance(tenantId, new BigDecimal("10.00")));
        assertEquals(requestCount + 1, store.summary(tenantId, BigDecimal.ZERO).totalRequests());
    }

    private static VirtualBillingRecord record(String traceId, BigDecimal cost) {
        return new VirtualBillingRecord(traceId, tenantId, "stub", "stub-echo-v1", CacheStatus.MISS,
                "SUCCESS", 1, 1, cost, BigDecimal.ZERO, Instant.now());
    }
}
