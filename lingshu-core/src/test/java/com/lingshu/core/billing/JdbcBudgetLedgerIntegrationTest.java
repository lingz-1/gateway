package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_POSTGRES_TESTS", matches = "true")
class JdbcBudgetLedgerIntegrationTest {

    private static HikariDataSource dataSource;
    private static JdbcBudgetLedger ledger;

    private final List<String> reservationIds = new ArrayList<>();

    @BeforeAll
    static void setUp() {
        String url = System.getenv().getOrDefault(
                "LINGSHU_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:54320/lingshu");
        String username = System.getenv().getOrDefault("LINGSHU_POSTGRES_USER", "lingshu");
        String password = System.getenv("LINGSHU_POSTGRES_PASSWORD");
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(16);
        dataSource = new HikariDataSource(config);
        ledger = new JdbcBudgetLedger(dataSource);
    }

    @AfterEach
    void cleanRows() throws Exception {
        if (reservationIds.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            for (String reservationId : reservationIds) {
                try (PreparedStatement outbox = connection.prepareStatement(
                        "DELETE FROM lingshu_budget_outbox WHERE reservation_id = ?")) {
                    outbox.setString(1, reservationId);
                    outbox.executeUpdate();
                }
                try (PreparedStatement ledgerDelete = connection.prepareStatement(
                        "DELETE FROM lingshu_budget_ledger WHERE reservation_id = ?")) {
                    ledgerDelete.setString(1, reservationId);
                    ledgerDelete.executeUpdate();
                }
            }
        }
        reservationIds.clear();
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    @RepeatedTest(value = 10, name = "ledger-confirm-{currentRepetition}")
    void concurrentConfirmCreatesOneTransitionAndOneOutboxEvent() throws Exception {
        assertSingleTransition("CONFIRMED", () -> ledger.confirm(reservationIds.getFirst()));
    }

    @RepeatedTest(value = 10, name = "ledger-release-{currentRepetition}")
    void concurrentReleaseCreatesOneTransitionAndOneOutboxEvent() throws Exception {
        assertSingleTransition("RELEASED", () -> ledger.release(reservationIds.getFirst()));
    }

    @RepeatedTest(value = 10, name = "ledger-settle-{currentRepetition}")
    void concurrentSettlementIsIdempotentAndCreatesOneActualUsageEvent() throws Exception {
        String reservationId = recordReservation();

        List<Optional<BudgetSettlement>> results = runConcurrentSettlements(30, () ->
                ledger.settle(reservationId, 60, TokenUsageSource.MIXED)
        );

        assertEquals(30, results.stream().filter(Optional::isPresent).count());
        assertEquals("CONFIRMED", ledgerStatus(reservationId));
        assertEquals(1, outboxCount(reservationId, "CONFIRMED"));
        assertEquals("60:MIXED", settlementValues(reservationId));
        assertEquals("60:MIXED", outboxValues(reservationId));
        assertEquals(Optional.empty(), ledger.settle(
                reservationId, 61, TokenUsageSource.MIXED
        ));
    }

    private void assertSingleTransition(String expectedStatus, Supplier<Boolean> transition) throws Exception {
        String reservationId = recordReservation();

        List<Boolean> results = runConcurrent(30, transition);

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(expectedStatus, ledgerStatus(reservationId));
        assertEquals(1, outboxCount(reservationId, expectedStatus));
        assertFalse(transition.get());
    }

    private String recordReservation() {
        String reservationId = "budget-ledger-it-" + UUID.randomUUID();
        reservationIds.add(reservationId);
        ledger.recordReservation(new BudgetReservation(
                reservationId,
                "tenant-budget-it",
                100,
                Instant.now().plusSeconds(300),
                BudgetReservationStatus.PENDING
        ));
        return reservationId;
    }

    private List<Boolean> runConcurrent(int tasks, Supplier<Boolean> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < tasks; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.get();
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

    private List<Optional<BudgetSettlement>> runConcurrentSettlements(
            int tasks,
            Supplier<Optional<BudgetSettlement>> action
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<BudgetSettlement>>> futures = new ArrayList<>();
            for (int index = 0; index < tasks; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.get();
                }));
            }
            start.countDown();
            List<Optional<BudgetSettlement>> results = new ArrayList<>();
            for (Future<Optional<BudgetSettlement>> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private String ledgerStatus(String reservationId) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM lingshu_budget_ledger WHERE reservation_id = ?")) {
            statement.setString(1, reservationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private int outboxCount(String reservationId, String eventType) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM lingshu_budget_outbox WHERE reservation_id = ? AND event_type = ?")) {
            statement.setString(1, reservationId);
            statement.setString(2, eventType);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private String settlementValues(String reservationId) throws Exception {
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
                "SELECT units, usage_source FROM lingshu_budget_outbox WHERE reservation_id = ?")) {
            statement.setString(1, reservationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) + ":" + resultSet.getString(2);
            }
        }
    }
}
