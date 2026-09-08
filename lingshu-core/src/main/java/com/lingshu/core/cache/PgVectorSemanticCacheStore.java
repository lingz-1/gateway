package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.cache.semantic",
        name = "enabled",
        havingValue = "true"
)
public class PgVectorSemanticCacheStore implements SemanticCacheStore {

    private static final int PRODUCTION_EMBEDDING_DIMENSION = 2560;

    private static final String FIND_SQL = """
            WITH query_vector AS (SELECT CAST(? AS halfvec) AS embedding)
            SELECT cache.response_json::text,
                   1 - (cache.embedding <=> query_vector.embedding) AS similarity
            FROM lingshu_semantic_cache_2560 cache
            CROSS JOIN query_vector
            WHERE cache.tenant_id = ?
              AND cache.scope_key = ?
              AND cache.expires_at > CURRENT_TIMESTAMP
              AND 1 - (cache.embedding <=> query_vector.embedding) >= ?
            ORDER BY cache.embedding <=> query_vector.embedding
            LIMIT 1
            """;

    private static final String PUT_SQL = """
            INSERT INTO lingshu_semantic_cache_2560 (
                tenant_id, scope_key, model, prompt_version, request_hash,
                request_text, embedding, response_json, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS halfvec), CAST(? AS jsonb), ?)
            ON CONFLICT (tenant_id, scope_key, request_hash)
            DO UPDATE SET
                request_text = EXCLUDED.request_text,
                embedding = EXCLUDED.embedding,
                response_json = EXCLUDED.response_json,
                expires_at = EXCLUDED.expires_at
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public PgVectorSemanticCacheStore(
            @Qualifier("semanticCacheDataSource") DataSource dataSource,
            ObjectMapper objectMapper
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        initializeSchema();
    }

    @Override
    public Optional<SemanticCacheMatch> find(
            String tenantId,
            String scopeKey,
            double[] embedding,
            double similarityThreshold
    ) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setString(1, vectorLiteral(embedding));
            statement.setString(2, tenantId);
            statement.setString(3, scopeKey);
            statement.setDouble(4, similarityThreshold);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                ProviderResponse response = objectMapper.readValue(
                        resultSet.getString(1),
                        ProviderResponse.class
                );
                return Optional.of(new SemanticCacheMatch(response, resultSet.getDouble(2)));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot query pgvector semantic cache", exception);
        }
    }

    @Override
    public void put(SemanticCacheEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(PUT_SQL)) {
            statement.setString(1, entry.tenantId());
            statement.setString(2, entry.scopeKey());
            statement.setString(3, entry.model());
            statement.setString(4, entry.promptVersion());
            statement.setString(5, entry.requestHash());
            statement.setString(6, entry.requestText());
            statement.setString(7, vectorLiteral(entry.embedding()));
            statement.setString(8, objectMapper.writeValueAsString(entry.response()));
            statement.setTimestamp(9, Timestamp.from(entry.expiresAt()));
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot write pgvector semantic cache", exception);
        }
    }

    private void initializeSchema() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS lingshu_semantic_cache_2560 (
                        id BIGSERIAL PRIMARY KEY,
                        tenant_id VARCHAR(128) NOT NULL,
                        scope_key CHAR(64) NOT NULL,
                        model VARCHAR(255) NOT NULL,
                        prompt_version VARCHAR(128) NOT NULL,
                        request_hash CHAR(64) NOT NULL,
                        request_text TEXT NOT NULL,
                        embedding halfvec(2560) NOT NULL,
                        response_json JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at TIMESTAMPTZ NOT NULL,
                        UNIQUE (tenant_id, scope_key, request_hash)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_lingshu_semantic_cache_2560_lookup
                    ON lingshu_semantic_cache_2560 (tenant_id, scope_key, expires_at)
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_lingshu_semantic_cache_2560_embedding_hnsw
                    ON lingshu_semantic_cache_2560
                    USING hnsw (embedding halfvec_cosine_ops)
                    """);
            statement.execute("""
                    INSERT INTO lingshu_semantic_cache_2560 (
                        tenant_id, scope_key, model, prompt_version, request_hash,
                        request_text, embedding, response_json, created_at, expires_at
                    )
                    SELECT tenant_id, scope_key, model, prompt_version, request_hash,
                           request_text, embedding::halfvec, response_json, created_at, expires_at
                    FROM lingshu_semantic_cache
                    WHERE vector_dims(embedding) = 2560
                    ON CONFLICT (tenant_id, scope_key, request_hash) DO NOTHING
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot initialize pgvector semantic cache schema", exception);
        }
    }

    private String vectorLiteral(double[] embedding) {
        if (embedding.length != PRODUCTION_EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException(
                    "Production semantic cache requires " + PRODUCTION_EMBEDDING_DIMENSION
                            + " dimensions"
            );
        }
        return DoubleStream.of(embedding)
                .mapToObj(value -> String.format(Locale.ROOT, "%.12f", value))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
