package com.dev.order_service.event;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final JdbcTemplate jdbcTemplate;

    public void recordCartItemAdded(CartItemAddedEvent event) {
        jdbcTemplate.update(
                "INSERT INTO order_event_outbox " +
                        "(order_id, buyer_id, event_id, item_count, quantity, occurred_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                event.orderId(), event.buyerId(), event.eventId(),
                event.itemCount(), event.quantity(), Timestamp.from(event.occurredAt())
        );
    }
}
