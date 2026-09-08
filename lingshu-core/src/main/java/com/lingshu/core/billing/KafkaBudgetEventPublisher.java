package com.lingshu.core.billing;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "lingshu.kafka", name = "enabled", havingValue = "true")
public class KafkaBudgetEventPublisher implements BudgetEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    @Autowired
    public KafkaBudgetEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            LingShuProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = properties.getBilling().getOutbox().getKafkaTopic();
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Billing Kafka topic must not be blank");
        }
    }

    @Override
    public void publish(BudgetOutboxEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, event.reservationId(), payload)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot publish budget event to Kafka", exception);
        }
    }
}
