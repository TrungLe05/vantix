package com.dev.commonlib_kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code order.tickets-issued}. Key = orderId.
 * Producer: order-service — vé đã phát hành sau khi thanh toán thành công.
 * Consumer: notification-service (P5) — báo buyer đặt vé thành công.
 * Không chứa mã vé: mã chỉ trả cho chính buyer qua API của Order.
 */
public record TicketsIssuedEvent(
        UUID orderId,
        UUID buyerId,
        UUID eventId,
        int ticketCount,
        BigDecimal totalAmount,
        String currency,
        Instant issuedAt
) {
}
