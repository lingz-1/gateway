package com.lingshu.core.billing;

import com.lingshu.common.dto.CacheStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "lingshu.billing.virtual", name = "persistence-enabled", havingValue = "true")
public class JdbcVirtualBillingStore implements VirtualBillingStore {

    private final DataSource dataSource;

    public JdbcVirtualBillingStore(@Qualifier("virtualBillingDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public BigDecimal getOrCreateBalance(String tenantId, BigDecimal initialBalanceCny) {
        String sql = """
                INSERT INTO lingshu_virtual_billing_accounts (tenant_id, balance_cny)
                VALUES (?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """;
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tenantId);
                statement.setBigDecimal(2, initialBalanceCny);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT balance_cny FROM lingshu_virtual_billing_accounts WHERE tenant_id = ?")) {
                statement.setString(1, tenantId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getBigDecimal(1);
                    }
                }
            }
            throw new IllegalStateException("Virtual billing account was not created");
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot load virtual billing account", exception);
        }
    }

    @Override
    public VirtualBillingRecord recordAtomically(VirtualBillingRecord proposed, BigDecimal initialBalanceCny) {
        String createAccount = """
                INSERT INTO lingshu_virtual_billing_accounts (tenant_id, balance_cny)
                VALUES (?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """;
        String lockAccount = "SELECT balance_cny FROM lingshu_virtual_billing_accounts WHERE tenant_id = ? FOR UPDATE";
        String updateAccount = """
                UPDATE lingshu_virtual_billing_accounts
                SET balance_cny = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ?
                """;
        String insertUsage = """
                INSERT INTO lingshu_virtual_billing_usage
                    (trace_id, tenant_id, provider, model, cache_status, status,
                     input_tokens, output_tokens, cost_cny, remaining_balance_cny, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trace_id) DO NOTHING
                """;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Serialize duplicate trace IDs before locking a tenant account.
            try (PreparedStatement traceLock = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                traceLock.setString(1, proposed.traceId());
                traceLock.executeQuery();
            }
            Optional<VirtualBillingRecord> existing = findByTraceId(connection, proposed.traceId());
            if (existing.isPresent()) {
                connection.commit();
                ensureSameTenant(existing.get(), proposed.tenantId());
                return existing.get();
            }
            try (PreparedStatement account = connection.prepareStatement(createAccount)) {
                account.setString(1, proposed.tenantId());
                account.setBigDecimal(2, initialBalanceCny);
                account.executeUpdate();
            }
            BigDecimal currentBalance;
            try (PreparedStatement account = connection.prepareStatement(lockAccount)) {
                account.setString(1, proposed.tenantId());
                try (ResultSet resultSet = account.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Virtual billing account was not created");
                    }
                    currentBalance = resultSet.getBigDecimal(1);
                }
            }
            BigDecimal remainingBalance = currentBalance.subtract(proposed.costCny());
            VirtualBillingRecord record = new VirtualBillingRecord(
                    proposed.traceId(), proposed.tenantId(), proposed.provider(), proposed.model(),
                    proposed.cacheStatus(), proposed.status(), proposed.inputTokens(), proposed.outputTokens(),
                    proposed.costCny(), remainingBalance, proposed.createdAt());
            try (PreparedStatement usage = connection.prepareStatement(insertUsage)) {
                usage.setString(1, record.traceId());
                usage.setString(2, record.tenantId());
                usage.setString(3, record.provider());
                usage.setString(4, record.model());
                usage.setString(5, record.cacheStatus() == null ? null : record.cacheStatus().name());
                usage.setString(6, record.status());
                usage.setInt(7, record.inputTokens());
                usage.setInt(8, record.outputTokens());
                usage.setBigDecimal(9, record.costCny());
                usage.setBigDecimal(10, record.remainingBalanceCny());
                usage.setTimestamp(11, Timestamp.from(record.createdAt()));
                int inserted = usage.executeUpdate();
                if (inserted != 1) {
                    throw new IllegalStateException("Virtual billing trace was not inserted");
                }
                try (PreparedStatement account = connection.prepareStatement(updateAccount)) {
                    account.setBigDecimal(1, record.remainingBalanceCny());
                    account.setString(2, record.tenantId());
                    account.executeUpdate();
                }
            }
            connection.commit();
            return record;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot persist virtual billing record", exception);
        }
    }

    @Override
    public Optional<VirtualBillingRecord> findByTraceId(String traceId) {
        String sql = "SELECT * FROM lingshu_virtual_billing_usage WHERE trace_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, traceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot find virtual billing record", exception);
        }
    }

    private Optional<VirtualBillingRecord> findByTraceId(Connection connection, String traceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM lingshu_virtual_billing_usage WHERE trace_id = ?")) {
            statement.setString(1, traceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        }
    }

    private void ensureSameTenant(VirtualBillingRecord record, String tenantId) {
        if (!record.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("traceId is already used by another tenant");
        }
    }

    @Override
    public VirtualBillingSummary summary(String tenantId, BigDecimal fallbackBalanceCny) {
        String sql = """
                SELECT COUNT(*), COUNT(*) FILTER (WHERE status = 'SUCCESS'),
                       COUNT(*) FILTER (WHERE status = 'FAILED'),
                       COUNT(*) FILTER (WHERE cache_status IN ('EXACT', 'SEMANTIC')),
                       COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                       COALESCE(SUM(cost_cny), 0)
                FROM lingshu_virtual_billing_usage WHERE tenant_id = ?
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                BigDecimal balance = getOrCreateBalance(tenantId, fallbackBalanceCny);
                return new VirtualBillingSummary(tenantId, balance, resultSet.getLong(1), resultSet.getLong(2),
                        resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5), resultSet.getLong(6), resultSet.getBigDecimal(7));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot query virtual billing summary", exception);
        }
    }

    @Override
    public List<VirtualBillingRecord> recent(String tenantId, int limit) {
        String sql = "SELECT * FROM lingshu_virtual_billing_usage WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?";
        List<VirtualBillingRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setInt(2, Math.min(Math.max(limit, 1), 100));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) records.add(read(resultSet));
            }
            return List.copyOf(records);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot query virtual billing records", exception);
        }
    }

    private VirtualBillingRecord read(ResultSet resultSet) throws SQLException {
        String cache = resultSet.getString("cache_status");
        return new VirtualBillingRecord(resultSet.getString("trace_id"), resultSet.getString("tenant_id"),
                resultSet.getString("provider"), resultSet.getString("model"),
                cache == null ? null : CacheStatus.valueOf(cache), resultSet.getString("status"),
                resultSet.getInt("input_tokens"), resultSet.getInt("output_tokens"),
                resultSet.getBigDecimal("cost_cny"), resultSet.getBigDecimal("remaining_balance_cny"),
                resultSet.getTimestamp("created_at").toInstant());
    }
}
