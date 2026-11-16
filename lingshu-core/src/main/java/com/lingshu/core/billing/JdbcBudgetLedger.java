package com.lingshu.core.billing;

import com.lingshu.common.dto.TokenUsageSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.billing",
        name = "enabled",
        havingValue = "true"
)
public class JdbcBudgetLedger implements BudgetLedger {

    private static final String INSERT_RESERVATION_SQL = """
            INSERT INTO lingshu_budget_ledger (
                reservation_id, tenant_id, units, status, expires_at
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (reservation_id) DO NOTHING
            """;

    private static final String CONFIRM_SQL = """
            UPDATE lingshu_budget_ledger
            SET status = 'CONFIRMED', updated_at = CURRENT_TIMESTAMP
            WHERE reservation_id = ? AND status = 'PENDING'
            """;

    private static final String RELEASE_SQL = """
            UPDATE lingshu_budget_ledger
            SET status = 'RELEASED', updated_at = CURRENT_TIMESTAMP
            WHERE reservation_id = ? AND status = 'PENDING'
            """;

    private static final String SETTLE_SQL = """
            UPDATE lingshu_budget_ledger
            SET status = 'CONFIRMED',
                actual_units = ?,
                released_units = units - ?,
                usage_source = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE reservation_id = ? AND status = 'PENDING'
            RETURNING reservation_id, tenant_id, units, actual_units, released_units, usage_source
            """;

    private static final String FIND_SETTLEMENT_SQL = """
            SELECT reservation_id, tenant_id, units, actual_units, released_units, usage_source
            FROM lingshu_budget_ledger
            WHERE reservation_id = ? AND status = 'CONFIRMED'
            """;

    private static final String OUTBOX_SQL = """
            INSERT INTO lingshu_budget_outbox (
                reservation_id, event_type, tenant_id, units, usage_source
            )
            SELECT reservation_id, ?, tenant_id, COALESCE(actual_units, units),
                   COALESCE(usage_source, 'UNKNOWN')
            FROM lingshu_budget_ledger
            WHERE reservation_id = ?
            ON CONFLICT (reservation_id, event_type) DO NOTHING
            """;

    private static final String EXPIRED_SQL = """
            SELECT reservation_id
            FROM lingshu_budget_ledger
            WHERE status = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP
            ORDER BY expires_at
            LIMIT ?
            """;

    private final DataSource dataSource;

    @Autowired
    public JdbcBudgetLedger(@Qualifier("billingDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void recordReservation(BudgetReservation reservation) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_RESERVATION_SQL)) {
            statement.setString(1, reservation.reservationId());
            statement.setString(2, reservation.tenantId());
            statement.setLong(3, reservation.units());
            statement.setString(4, reservation.status().name());
            statement.setTimestamp(5, java.sql.Timestamp.from(reservation.expiresAt()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot record budget reservation", exception);
        }
    }

    @Override
    public boolean confirm(String reservationId) {
        return transition(reservationId, CONFIRM_SQL, "CONFIRMED");
    }

    @Override
    public Optional<BudgetSettlement> settle(
            String reservationId,
            long actualUnits,
            TokenUsageSource usageSource
    ) {
        if (reservationId == null || reservationId.isBlank()) {
            throw new IllegalArgumentException("reservationId must not be blank");
        }
        if (actualUnits < 0) {
            throw new IllegalArgumentException("actual units must not be negative");
        }
        if (usageSource == null || usageSource == TokenUsageSource.UNKNOWN) {
            throw new IllegalArgumentException("usageSource must be known");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                BudgetSettlement settlement = updateSettlement(
                        connection, reservationId, actualUnits, usageSource
                );
                if (settlement != null) {
                    insertOutbox(connection, reservationId, "CONFIRMED");
                    connection.commit();
                    return Optional.of(settlement);
                }
                connection.rollback();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot settle budget reservation", exception);
        }
        Optional<BudgetSettlement> existing = findSettlement(reservationId);
        return existing.filter(settlement ->
                settlement.actualUnits() == actualUnits
                        && settlement.usageSource() == usageSource
        );
    }

    @Override
    public boolean release(String reservationId) {
        return transition(reservationId, RELEASE_SQL, "RELEASED");
    }

    @Override
    public List<String> findExpiredReservationIds(int limit) {
        List<String> reservationIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(EXPIRED_SQL)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    reservationIds.add(resultSet.getString("reservation_id"));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot find expired budget reservations", exception);
        }
        return List.copyOf(reservationIds);
    }

    private boolean transition(String reservationId, String transitionSql, String eventType) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement transition = connection.prepareStatement(transitionSql)) {
                transition.setString(1, reservationId);
                int updated = transition.executeUpdate();
                if (updated == 1) {
                    insertOutbox(connection, reservationId, eventType);
                    connection.commit();
                    return true;
                }
                connection.rollback();
                return false;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot transition budget reservation", exception);
        }
    }

    private BudgetSettlement updateSettlement(
            Connection connection,
            String reservationId,
            long actualUnits,
            TokenUsageSource usageSource
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SETTLE_SQL)) {
            statement.setLong(1, actualUnits);
            statement.setLong(2, actualUnits);
            statement.setString(3, usageSource.name());
            statement.setString(4, reservationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? settlement(resultSet) : null;
            }
        }
    }

    private Optional<BudgetSettlement> findSettlement(String reservationId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_SETTLEMENT_SQL)) {
            statement.setString(1, reservationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(settlement(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read budget settlement", exception);
        }
    }

    private BudgetSettlement settlement(ResultSet resultSet) throws SQLException {
        return new BudgetSettlement(
                resultSet.getString("reservation_id"),
                resultSet.getString("tenant_id"),
                resultSet.getLong("units"),
                resultSet.getLong("actual_units"),
                resultSet.getLong("released_units"),
                TokenUsageSource.valueOf(resultSet.getString("usage_source"))
        );
    }

    private void insertOutbox(
            Connection connection,
            String reservationId,
            String eventType
    ) throws SQLException {
        try (PreparedStatement outbox = connection.prepareStatement(OUTBOX_SQL)) {
            outbox.setString(1, eventType);
            outbox.setString(2, reservationId);
            outbox.executeUpdate();
        }
    }
}
