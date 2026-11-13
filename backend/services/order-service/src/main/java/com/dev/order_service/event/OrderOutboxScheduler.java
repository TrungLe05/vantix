package com.dev.order_service.event;

import com.dev.commonlib_kafka.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderOutboxScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(fixedDelayString = "${app.order.outbox-poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxRecord> pending = jdbcTemplate.query(
                "SELECT id, order_id, buyer_id, event_id, item_count, quantity, occurred_at " +
                        "FROM order_event_outbox WHERE published_at IS NULL AND next_attempt_at <= now() " +
                        "ORDER BY created_at LIMIT 50",
                (rs, row) -> new OutboxRecord(
                        rs.getObject("id", UUID.class),
                        new CartItemAddedEvent(
                                rs.getObject("order_id", UUID.class),
                                rs.getObject("buyer_id", UUID.class),
                                rs.getObject("event_id", UUID.class),
                                rs.getInt("item_count"),
                                rs.getInt("quantity"),
                                rs.getTimestamp("occurred_at").toInstant()
                        )
                )
        );

        for (OutboxRecord record : pending) {
            try {
                kafkaTemplate.send(
                        KafkaTopics.ORDER_CART_ITEM_ADDED,
                        record.event().orderId().toString(),
                        record.event()
                ).get(5, TimeUnit.SECONDS);
                jdbcTemplate.update(
                        "UPDATE order_event_outbox SET published_at = now() WHERE id = ? AND published_at IS NULL",
                        record.id()
                );
            } catch (Exception ex) {
                jdbcTemplate.update(
                        "UPDATE order_event_outbox SET attempt_count = attempt_count + 1, " +
                                "next_attempt_at = now() + (5000 * interval '1 millisecond') " +
                                "WHERE id = ? AND published_at IS NULL",
                        record.id()
                );
                log.error("Không thể phát cart.item_added, orderId={}; sẽ retry", record.event().orderId(), ex);
            }
        }
    }

    private record OutboxRecord(UUID id, CartItemAddedEvent event) {
    }
}
