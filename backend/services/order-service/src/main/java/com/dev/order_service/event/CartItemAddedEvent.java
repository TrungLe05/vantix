package com.dev.order_service.event;

import java.time.Instant;
import java.util.UUID;

public record CartItemAddedEvent(
        UUID orderId,
        UUID buyerId,
        UUID eventId,
        int itemCount,
        int quantity,
        Instant occurredAt
) {
}
