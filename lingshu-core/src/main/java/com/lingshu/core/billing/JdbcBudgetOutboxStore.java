package com.lingshu.core.billing;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "lingshu.billing.outbox", name = "enabled", havingValue = "true")
public class JdbcBudgetOutboxStore implements BudgetOutboxStore {

    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT event_id
                FROM lingshu_budget_outbox
                WHERE published_at IS NULL
                  AND (claimed_at IS NULL OR claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second'))
                ORDER BY event_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE lingshu_budget_outbox outbox
            SET claim_token = ?, claimed_at = CURRENT_TIMESTAMP
            WHERE outbox.event_id IN (SELECT event_id FROM candidates)
            RETURNING outbox.event_id, outbox.reservation_id, outbox.event_type,
                      outbox.tenant_id, outbox.units, outbox.created_at, outbox.claim_token
            """;

    private static final String MARK_PUBLISHED_SQL = """
            UPDATE lingshu_budget_outbox
            SET published_at = CURRENT_TIMESTAMP, claimed_at = NULL, claim_token = NULL
            WHERE event_id = ? AND published_at IS NULL AND claim_token = ?
            """;

    private final DataSource dataSource;
    private final int batchSize;
    private final long claimTimeoutSeconds;

    @Autowired
    public JdbcBudgetOutboxStore(
            @Qualifier("outboxDataSource") DataSource dataSource,
            LingShuProperties properties
    ) {
        this.dataSource = dataSource;
        LingShuProperties.Billing.Outbox outbox = properties.getBilling().getOutbox();
        if (outbox.getBatchSize() <= 0) {
            throw new IllegalArgumentException("Billing outbox batch size must be positive");
        }
        Duration claimTimeout = outbox.getClaimTimeout();
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()) {
            throw new IllegalArgumentException("Billing outbox claim timeout must be positive");
        }
        this.batchSize = outbox.getBatchSize();
        this.claimTimeoutSeconds = Math.max(1, claimTimeout.toSeconds());
    }

    @Override
    public List<BudgetOutboxEvent> claimUnpublished(int limit) {
        int requestedLimit = Math.min(Math.max(1, limit), batchSize);
        UUID claimToken = UUID.randomUUID();
        List<BudgetOutboxEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLAIM_SQL)) {
            statement.setLong(1, claimTimeoutSeconds);
            statement.setInt(2, requestedLimit);
            statement.setObject(3, claimToken);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(new BudgetOutboxEvent(
                            resultSet.getLong("event_id"),
                            resultSet.getString("reservation_id"),
                            resultSet.getString("event_type"),
                            resultSet.getString("tenant_id"),
                            resultSet.getLong("units"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getString("claim_token")
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot claim budget outbox events", exception);
        }
        return List.copyOf(events);
    }

    @Override
    public boolean markPublished(BudgetOutboxEvent event) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARK_PUBLISHED_SQL)) {
            statement.setLong(1, event.eventId());
            statement.setObject(2, UUID.fromString(event.claimToken()));
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot mark budget outbox event published", exception);
        }
    }
}
