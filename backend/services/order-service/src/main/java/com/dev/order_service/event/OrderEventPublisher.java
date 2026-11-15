package com.dev.order_service.event;

import com.dev.commonlib_kafka.event.OrderRefundedEvent;
import com.dev.commonlib_kafka.event.RefundRequestedEvent;
import com.dev.commonlib_kafka.event.TicketsIssuedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public void recordCartItemAdded(CartItemAddedEvent event) {
        jdbcTemplate.update(
                "INSERT INTO order_event_outbox " +
                        "(order_id, buyer_id, event_id, item_count, quantity, occurred_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                event.orderId(), event.buyerId(), event.eventId(),
                event.itemCount(), event.quantity(), Timestamp.from(event.occurredAt())
        );
    }

    // Event mới (P4) đi qua cột topic + payload JSON; cart.item_added giữ dạng cột riêng như cũ.
    // Gọi trong transaction nghiệp vụ để yêu cầu hoàn và thay đổi trạng thái Order cùng commit.
    public void recordRefundRequested(RefundRequestedEvent event, UUID eventId) {
        recordJson(KafkaTopics.ORDER_REFUND_REQUESTED, event.orderId(), event.buyerId(), eventId,
                event.requestedAt(), event);
    }

    public void recordTicketsIssued(TicketsIssuedEvent event) {
        recordJson(KafkaTopics.ORDER_TICKETS_ISSUED, event.orderId(), event.buyerId(), event.eventId(),
                event.issuedAt(), event);
    }

    public void recordOrderRefunded(OrderRefundedEvent event) {
        recordJson(KafkaTopics.ORDER_REFUNDED, event.orderId(), event.buyerId(), event.eventId(),
                event.completedAt(), event);
    }

    private void recordJson(String topic, UUID orderId, UUID buyerId, UUID eventId, Instant occurredAt, Object event) {
        jdbcTemplate.update(
                "INSERT INTO order_event_outbox (order_id, buyer_id, event_id, occurred_at, topic, payload) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, buyerId, eventId, Timestamp.from(occurredAt), topic, jsonMapper.writeValueAsString(event)
        );
    }
}
