package com.lingshu.core.billing;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.common.dto.TokenUsageSource;
import com.lingshu.core.config.LingShuProperties;
import com.lingshu.core.provider.ModelProvider;
import com.lingshu.core.provider.ProviderHealth;
import com.lingshu.core.provider.ProviderUsageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_REDIS_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_POSTGRES_TESTS", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "lingshu.billing.enabled=true",
                "lingshu.billing.outbox.enabled=false",
                "lingshu.billing.reservation-default-output-units=20",
                "lingshu.database.migration-enabled=true",
                "lingshu.cache.exact.enabled=false",
                "lingshu.cache.semantic.enabled=false",
                "lingshu.provider.stub.stream-delay=100ms",
                "lingshu.provider.stub.stream-chunks=5"
        }
)
@Import(BudgetPipelineIntegrationTest.ProviderConfiguration.class)
class BudgetPipelineIntegrationTest {

    private static final String FAILURE_MODEL = "budget-failure-model";
    private static final String FALLBACK_MODEL = "budget-fallback-model";
    private static final String OVERAGE_MODEL = "budget-overage-model";
    private static final String ESTIMATED_MODEL = "budget-estimated-model";
    private static final String UNKNOWN_USAGE_MODEL = "budget-unknown-usage-model";

    @LocalServerPort
    private int port;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private LingShuProperties properties;

    @Autowired
    @Qualifier("billingDataSource")
    private DataSource dataSource;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<String> tenantIds = new ArrayList<>();
    private final List<String> traceIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (String tenantId : tenantIds) {
            redisTemplate.delete(balanceKey(tenantId));
        }
        for (String traceId : traceIds) {
            redisTemplate.delete(reservationKey(traceId));
        }
        try (Connection connection = dataSource.getConnection()) {
            for (String traceId : traceIds) {
                try (PreparedStatement outbox = connection.prepareStatement(
                        "DELETE FROM lingshu_budget_outbox WHERE reservation_id = ?")) {
                    outbox.setString(1, traceId);
                    outbox.executeUpdate();
                }
                try (PreparedStatement ledger = connection.prepareStatement(
                        "DELETE FROM lingshu_budget_ledger WHERE reservation_id = ?")) {
                    ledger.setString(1, traceId);
                    ledger.executeUpdate();
                }
            }
        }
        tenantIds.clear();
        traceIds.clear();
    }

    @Test
    void rejectsInsufficientBudgetBeforeProviderInvocation() throws Exception {
        Scenario scenario = scenario(1);

        HttpResponse<String> response = post(
                "stub-echo-v1", scenario, false, "insufficient", 20
        );

        assertEquals(429, response.statusCode());
        assertTrue(response.body().contains("BUDGET_EXCEEDED"));
        assertEquals("1", balance(scenario.tenantId()));
        assertEquals(0, ledgerCount(scenario.traceId()));
    }

    @Test
    void settlesNormalResponseAndReleasesReservationDifference() throws Exception {
        Scenario scenario = scenario(100);

        HttpResponse<String> response = post(
                "stub-echo-v1", scenario, false, "hello", 20
        );

        assertEquals(200, response.statusCode());
        SettlementRow row = settlement(scenario.traceId());
        assertEquals("CONFIRMED", row.status());
        assertEquals(7, row.actualUnits());
        assertEquals(TokenUsageSource.PROVIDER_REPORTED, row.usageSource());
        assertEquals("93", balance(scenario.tenantId()));
        assertEquals(1, outboxCount(scenario.traceId(), "CONFIRMED"));
    }

    @Test
    void releasesReservationWhenAllProvidersFailWithoutUsage() throws Exception {
        Scenario scenario = scenario(100);

        HttpResponse<String> response = post(
                FAILURE_MODEL, scenario, false, "failure", 20
        );

        assertEquals(503, response.statusCode());
        assertEquals("100", balance(scenario.tenantId()));
        assertEquals("RELEASED", settlement(scenario.traceId()).status());
        assertEquals(1, outboxCount(scenario.traceId(), "RELEASED"));
    }

    @Test
    void fallbackSettlesOnceAndMarksCombinedUsageAsMixed() throws Exception {
        Scenario scenario = scenario(100);

        HttpResponse<String> response = post(
                FALLBACK_MODEL, scenario, false, "fallback", 20
        );

        assertEquals(200, response.statusCode());
        SettlementRow row = settlement(scenario.traceId());
        assertEquals(10, row.actualUnits());
        assertEquals(TokenUsageSource.MIXED, row.usageSource());
        assertEquals("90", balance(scenario.tenantId()));
        assertEquals(1, outboxCount(scenario.traceId(), "CONFIRMED"));
    }

    @Test
    void chargesOverageThroughDefaultPipeline() throws Exception {
        Scenario scenario = scenario(25);

        HttpResponse<String> response = post(
                OVERAGE_MODEL, scenario, false, "overage", 20
        );

        assertEquals(200, response.statusCode());
        SettlementRow row = settlement(scenario.traceId());
        assertEquals("CONFIRMED", row.status());
        assertEquals(30, row.actualUnits());
        assertEquals(TokenUsageSource.PROVIDER_REPORTED, row.usageSource());
        assertEquals("-5", balance(scenario.tenantId()));
        assertEquals(1, outboxCount(scenario.traceId(), "CONFIRMED"));
    }

    @Test
    void preservesEstimatedUsageSource() throws Exception {
        Scenario scenario = scenario(100);

        HttpResponse<String> response = post(
                ESTIMATED_MODEL, scenario, false, "estimate", 20
        );

        assertEquals(200, response.statusCode());
        SettlementRow row = settlement(scenario.traceId());
        assertEquals(7, row.actualUnits());
        assertEquals(TokenUsageSource.ESTIMATED, row.usageSource());
        assertEquals("93", balance(scenario.tenantId()));
    }

    @Test
    void estimatesMissingUsageAndPersistsEstimatedSource() throws Exception {
        Scenario scenario = scenario(100);

        HttpResponse<String> response = post(
                UNKNOWN_USAGE_MODEL, scenario, false, "missing-usage", 20
        );

        assertEquals(200, response.statusCode());
        SettlementRow row = settlement(scenario.traceId());
        assertTrue(row.actualUnits() > 0);
        assertEquals(TokenUsageSource.ESTIMATED, row.usageSource());
        assertEquals(Long.toString(100 - row.actualUnits()), balance(scenario.tenantId()));
    }

    @Test
    void completesSseAndSettlesActualUsage() throws Exception {
        Scenario scenario = scenario(100);

        HttpResponse<String> response = post(
                "stub-echo-v1", scenario, true, "stream", 20
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("[DONE]"));
        SettlementRow row = settlement(scenario.traceId());
        assertEquals("CONFIRMED", row.status());
        assertEquals(TokenUsageSource.PROVIDER_REPORTED, row.usageSource());
        assertEquals(Long.toString(100 - row.actualUnits()), balance(scenario.tenantId()));
    }

    @Test
    void explicitStreamCancellationReleasesReservation() throws Exception {
        Scenario scenario = scenario(100);
        HttpRequest streamRequest = request(
                "stub-echo-v1", scenario, true, "cancel-me", 20
        );
        CompletableFuture<HttpResponse<String>> stream = httpClient.sendAsync(
                streamRequest, HttpResponse.BodyHandlers.ofString()
        );

        HttpResponse<String> cancellation = waitForCancellation(scenario);
        HttpResponse<String> response = stream.get();

        assertEquals(202, cancellation.statusCode());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("STREAM_CANCELLED"));
        awaitReleased(scenario);
        assertEquals("100", balance(scenario.tenantId()));
        assertEquals("RELEASED", settlement(scenario.traceId()).status());
    }

    @Test
    void concurrentDuplicateTraceSettlesBalanceAndOutboxOnlyOnce() throws Exception {
        Scenario scenario = scenario(100);
        int concurrency = 16;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return post("stub-echo-v1", scenario, false, "duplicate", 20);
                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> future : futures) {
                assertEquals(200, future.get().statusCode());
            }
        }

        SettlementRow row = settlement(scenario.traceId());
        assertEquals("CONFIRMED", row.status());
        assertEquals(Long.toString(100 - row.actualUnits()), balance(scenario.tenantId()));
        assertEquals(1, outboxCount(scenario.traceId(), "CONFIRMED"));
    }

    private HttpResponse<String> waitForCancellation(Scenario scenario) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port
                + "/v1/stream-cancellations/" + scenario.traceId());
        for (int attempt = 0; attempt < 50; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("X-Tenant-Id", scenario.tenantId())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 404) {
                return response;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Stream did not become cancellable");
    }

    private void awaitReleased(Scenario scenario) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if ("100".equals(balance(scenario.tenantId()))
                    && ledgerCount(scenario.traceId()) == 1
                    && "RELEASED".equals(settlement(scenario.traceId()).status())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Cancelled stream budget was not released");
    }

    private HttpResponse<String> post(
            String model,
            Scenario scenario,
            boolean stream,
            String prompt,
            int maxTokens
    ) throws Exception {
        return httpClient.send(
                request(model, scenario, stream, prompt, maxTokens),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpRequest request(
            String model,
            Scenario scenario,
            boolean stream,
            String prompt,
            int maxTokens
    ) {
        String body = "{\"model\":\"" + model + "\",\"stream\":" + stream
                + ",\"max_tokens\":" + maxTokens
                + ",\"messages\":[{\"role\":\"user\",\"content\":\""
                + prompt + "\"}]}";
        return HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .header("X-Trace-Id", scenario.traceId())
                .header("X-Tenant-Id", scenario.tenantId())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private Scenario scenario(long balance) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenantId = "budget-pipeline-" + suffix;
        String traceId = "budget-pipeline-trace-" + suffix;
        tenantIds.add(tenantId);
        traceIds.add(traceId);
        redisTemplate.opsForValue().set(balanceKey(tenantId), Long.toString(balance));
        return new Scenario(tenantId, traceId);
    }

    private String balance(String tenantId) {
        return redisTemplate.opsForValue().get(balanceKey(tenantId));
    }

    private String balanceKey(String tenantId) {
        return properties.getBilling().getRedisKeyPrefix() + "balance:" + tenantId;
    }

    private String reservationKey(String traceId) {
        return properties.getBilling().getRedisKeyPrefix() + "reservation:" + traceId;
    }

    private int ledgerCount(String traceId) throws Exception {
        return count("SELECT COUNT(*) FROM lingshu_budget_ledger WHERE reservation_id = ?", traceId);
    }

    private int outboxCount(String traceId, String eventType) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT COUNT(*) FROM lingshu_budget_outbox
                             WHERE reservation_id = ? AND event_type = ?
                             """)) {
            statement.setString(1, traceId);
            statement.setString(2, eventType);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private int count(String sql, String traceId) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private SettlementRow settlement(String traceId) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                     connection.prepareStatement("""
                             SELECT status, actual_units, usage_source
                             FROM lingshu_budget_ledger WHERE reservation_id = ?
                             """)) {
            statement.setString(1, traceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                String source = resultSet.getString("usage_source");
                return new SettlementRow(
                        resultSet.getString("status"),
                        resultSet.getLong("actual_units"),
                        source == null ? null : TokenUsageSource.valueOf(source)
                );
            }
        }
    }

    private record Scenario(String tenantId, String traceId) {
    }

    private record SettlementRow(
            String status,
            long actualUnits,
            TokenUsageSource usageSource
    ) {
    }

    @TestConfiguration
    static class ProviderConfiguration {

        @Bean
        ModelProvider budgetFailureProvider() {
            return provider("budget-failure", FAILURE_MODEL, request -> {
                throw new IllegalStateException("Injected provider failure");
            });
        }

        @Bean
        ModelProvider budgetFallbackFailureProvider() {
            return provider("a-budget-fallback", FALLBACK_MODEL, request -> {
                throw new ProviderUsageException("Injected fallback failure", 2, 1);
            });
        }

        @Bean
        ModelProvider budgetFallbackSuccessProvider() {
            return provider("b-budget-fallback", FALLBACK_MODEL, request -> new ProviderResponse(
                    "b-budget-fallback", FALLBACK_MODEL, "fallback", 3, 4,
                    TokenUsageSource.PROVIDER_REPORTED, "stop", List.of()
            ));
        }

        @Bean
        ModelProvider budgetEstimatedProvider() {
            return provider("budget-estimated", ESTIMATED_MODEL, request -> new ProviderResponse(
                    "budget-estimated", ESTIMATED_MODEL, "estimated", 3, 4,
                    TokenUsageSource.ESTIMATED, "stop", List.of()
            ));
        }

        @Bean
        ModelProvider budgetOverageProvider() {
            return provider("budget-overage", OVERAGE_MODEL, request -> new ProviderResponse(
                    "budget-overage", OVERAGE_MODEL, "overage", 10, 20,
                    TokenUsageSource.PROVIDER_REPORTED, "stop", List.of()
            ));
        }

        @Bean
        ModelProvider budgetUnknownUsageProvider() {
            return provider("budget-unknown-usage", UNKNOWN_USAGE_MODEL, request -> new ProviderResponse(
                    "budget-unknown-usage", UNKNOWN_USAGE_MODEL, "unknown usage", 0, 0,
                    TokenUsageSource.UNKNOWN, "stop", List.of()
            ));
        }

        private ModelProvider provider(
                String id,
                String model,
                java.util.function.Function<ProviderRequest, ProviderResponse> invocation
        ) {
            return new ModelProvider() {
                @Override
                public String id() {
                    return id;
                }

                @Override
                public Set<String> supportedModels() {
                    return Set.of(model);
                }

                @Override
                public ProviderResponse invoke(ProviderRequest request) {
                    return invocation.apply(request);
                }

                @Override
                public ProviderHealth health() {
                    return new ProviderHealth(id, ProviderHealth.Status.UP, Instant.now());
                }
            };
        }
    }
}
