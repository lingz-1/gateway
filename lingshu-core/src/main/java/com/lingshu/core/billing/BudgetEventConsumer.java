package com.lingshu.core.billing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Component
@ConditionalOnProperty(prefix = "lingshu.billing.outbox", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "lingshu.kafka", name = "enabled", havingValue = "true")
public class BudgetEventConsumer {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public BudgetEventConsumer(
            @Qualifier("outboxDataSource") DataSource dataSource,
            ObjectMapper objectMapper
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${lingshu.billing.outbox.kafka-topic}")
    public void consume(String payload) {
        long eventId = eventId(payload);
        if (eventId < 0) {
            throw new IllegalArgumentException("Billing event is missing eventId");
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lingshu_budget_event_consumption (event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING")) {
            statement.setLong(1, eventId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot record consumed billing event", exception);
        }
    }

    private long eventId(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            return root.path("eventId").asLong(-1);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Billing event payload is invalid", exception);
        }
    }
}
