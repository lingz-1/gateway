package com.lingshu.core.billing;

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

    private static final String OUTBOX_SQL = """
            INSERT INTO lingshu_budget_outbox (
                reservation_id, event_type, tenant_id, units
            )
            SELECT reservation_id, ?, tenant_id, units
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
                    try (PreparedStatement outbox = connection.prepareStatement(OUTBOX_SQL)) {
                        outbox.setString(1, eventType);
                        outbox.setString(2, reservationId);
                        outbox.executeUpdate();
                    }
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
}
