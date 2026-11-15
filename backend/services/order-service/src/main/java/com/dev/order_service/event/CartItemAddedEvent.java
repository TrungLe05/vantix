package com.dev.order_service.event;

import java.time.Instant;
import java.util.UUID;

public record CartItemAddedEvent(
        UUID orderId,
        UUID buyerId,
        UUID eventId,
        int itemCount,
        int quantity,
        Instant occurredAt,
        // P6 (D38): HMAC thiết bị và lần đầu thiết bị xem availability của event; null khi không có
        String deviceHash,
        Instant eventViewedAt
) {
}
