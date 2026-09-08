package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "LINGSHU_RUN_POSTGRES_TESTS", matches = "true")
class PgVectorSemanticCacheStoreIntegrationTest {

    private static final int EMBEDDING_DIMENSION = 2560;

    private static HikariDataSource dataSource;
    private static PgVectorSemanticCacheStore store;

    @BeforeAll
    static void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv().getOrDefault(
                "LINGSHU_POSTGRES_URL",
                "jdbc:postgresql://127.0.0.1:54320/lingshu"
        ));
        config.setUsername(System.getenv().getOrDefault("LINGSHU_POSTGRES_USER", "lingshu"));
        config.setPassword(System.getenv("LINGSHU_POSTGRES_PASSWORD"));
        config.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(config);
        store = new PgVectorSemanticCacheStore(dataSource, JsonMapper.builder().build());
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void writesAndFindsNearestEntry() {
        String tenantId = "integration-" + UUID.randomUUID();
        ProviderResponse response = new ProviderResponse("stub", "stub-echo-v1", "cached", 3, 1);
        store.put(new SemanticCacheEntry(
                tenantId,
                "a".repeat(64),
                "stub-echo-v1",
                "v1",
                "b".repeat(64),
                "reset password",
                vector(1.0, 0.0, 0.0),
                response,
                Instant.now().plusSeconds(60)
        ));

        SemanticCacheMatch match = store.find(
                tenantId,
                "a".repeat(64),
                vector(0.99, 0.01, 0.0),
                0.99
        ).orElseThrow();

        assertEquals(response, match.response());
    }

    private static double[] vector(double first, double second, double third) {
        double[] vector = new double[EMBEDDING_DIMENSION];
        vector[0] = first;
        vector[1] = second;
        vector[2] = third;
        return vector;
    }
}
