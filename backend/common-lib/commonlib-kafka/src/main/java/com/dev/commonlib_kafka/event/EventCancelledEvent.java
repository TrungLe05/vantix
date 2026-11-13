package com.dev.commonlib_kafka.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code product.event-cancelled}.
 * Producer: product-service (PR06 — hủy sự kiện).
 * Consumer: order-service (P3 — hủy Order chưa thanh toán/nhả hold; P4 — trigger refund 100% theo FR4).
 */
public record EventCancelledEvent(UUID eventId, Instant cancelledAt) {
}
