package com.dev.commonlib_kafka.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code product.event-cancelled}.
 * Producer: product-service (PR06 — hủy sự kiện).
 * Consumer: order-service (P4 — trigger hoàn tiền 100% theo FR4).
 */
public record EventCancelledEvent(UUID eventId, Instant cancelledAt) {
}
