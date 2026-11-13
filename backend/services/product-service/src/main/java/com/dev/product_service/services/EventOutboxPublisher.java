package com.dev.product_service.services;

import com.dev.commonlib_kafka.event.EventCancelledEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class EventOutboxPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxRecord> pending = jdbcTemplate.query(
                "SELECT id, event_id, (payload->>'cancelledAt') AS cancelled_at " +
                        "FROM event_outbox WHERE published_at IS NULL AND next_attempt_at <= now() " +
                        "ORDER BY created_at LIMIT 50",
                (rs, row) -> new OutboxRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getTimestamp("cancelled_at").toInstant()
                )
        );

        for (OutboxRecord record : pending) {
            try {
                kafkaTemplate.send(
                        KafkaTopics.PRODUCT_EVENT_CANCELLED,
                        record.eventId().toString(),
                        new EventCancelledEvent(record.eventId(), record.cancelledAt())
                ).get(5, TimeUnit.SECONDS);
                jdbcTemplate.update(
                        "UPDATE event_outbox SET published_at = now() WHERE id = ? AND published_at IS NULL",
                        record.id()
                );
            } catch (Exception ex) {
                jdbcTemplate.update(
                        "UPDATE event_outbox SET attempt_count = attempt_count + 1, " +
                                "next_attempt_at = now() + (? * interval '1 millisecond') " +
                                "WHERE id = ? AND published_at IS NULL",
                        Duration.ofSeconds(5).toMillis(), record.id()
                );
                log.error("Không thể phát event outbox eventId={}; sẽ retry", record.eventId(), ex);
            }
        }
    }

    private record OutboxRecord(UUID id, UUID eventId, java.time.Instant cancelledAt) {
    }
}
