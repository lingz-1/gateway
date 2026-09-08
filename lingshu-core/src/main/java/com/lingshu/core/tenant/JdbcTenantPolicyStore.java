package com.lingshu.core.tenant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(prefix = "lingshu.tenant-policy", name = "persistence-enabled", havingValue = "true")
public class JdbcTenantPolicyStore implements TenantPolicyStore {

    private final DataSource dataSource;

    public JdbcTenantPolicyStore(@Qualifier("tenantPolicyDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<TenantPolicy> find(String tenantId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM lingshu_tenant_policies WHERE tenant_id = ?")) {
            statement.setString(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot load tenant policy", exception);
        }
    }

    @Override
    public TenantPolicy save(TenantPolicy policy) {
        String sql = """
                INSERT INTO lingshu_tenant_policies
                    (tenant_id, enabled, allowed_models, pii_redaction_enabled, exact_cache_enabled, semantic_cache_enabled,
                     requests_per_minute, max_concurrent_requests, input_price_usd_per_million,
                     output_price_usd_per_million, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    allowed_models = EXCLUDED.allowed_models,
                    pii_redaction_enabled = EXCLUDED.pii_redaction_enabled,
                    exact_cache_enabled = EXCLUDED.exact_cache_enabled,
                    semantic_cache_enabled = EXCLUDED.semantic_cache_enabled,
                    requests_per_minute = EXCLUDED.requests_per_minute,
                    max_concurrent_requests = EXCLUDED.max_concurrent_requests,
                    input_price_usd_per_million = EXCLUDED.input_price_usd_per_million,
                    output_price_usd_per_million = EXCLUDED.output_price_usd_per_million,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING *
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, policy.tenantId());
            statement.setBoolean(2, policy.enabled());
            Array models = connection.createArrayOf("text", policy.allowedModels().toArray(String[]::new));
            statement.setArray(3, models);
            statement.setBoolean(4, policy.piiRedactionEnabled());
            statement.setBoolean(5, policy.exactCacheEnabled());
            statement.setBoolean(6, policy.semanticCacheEnabled());
            statement.setInt(7, policy.requestsPerMinute());
            statement.setInt(8, policy.maxConcurrentRequests());
            statement.setBigDecimal(9, policy.inputPriceUsdPerMillion());
            statement.setBigDecimal(10, policy.outputPriceUsdPerMillion());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return read(resultSet);
            } finally {
                models.free();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot save tenant policy", exception);
        }
    }

    private TenantPolicy read(ResultSet resultSet) throws SQLException {
        String[] models = (String[]) resultSet.getArray("allowed_models").getArray();
        Set<String> allowedModels = Arrays.stream(models).collect(Collectors.toUnmodifiableSet());
        return new TenantPolicy(
                resultSet.getString("tenant_id"),
                resultSet.getBoolean("enabled"),
                allowedModels,
                resultSet.getBoolean("pii_redaction_enabled"),
                resultSet.getBoolean("exact_cache_enabled"),
                resultSet.getBoolean("semantic_cache_enabled"),
                resultSet.getInt("requests_per_minute"),
                resultSet.getInt("max_concurrent_requests"),
                resultSet.getBigDecimal("input_price_usd_per_million"),
                resultSet.getBigDecimal("output_price_usd_per_million"),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
