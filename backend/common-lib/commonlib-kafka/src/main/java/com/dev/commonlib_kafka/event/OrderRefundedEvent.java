package com.dev.commonlib_kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code order.refunded}. Key = orderId.
 * Producer: order-service — sau khi nhận refund.completed từ Payment.
 * Consumer: notification-service (P5). Order phát lại thay vì để Notification đọc refund.completed
 * vì chỉ Order biết buyer và lý do hoàn (Payment chỉ biết tiền, D07).
 *
 * @param reason {@code BUYER_REQUEST}, {@code EVENT_CANCELLED} hoặc {@code LATE_PAYMENT}
 */
public record OrderRefundedEvent(
        UUID refundId,
        UUID orderId,
        UUID buyerId,
        UUID eventId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant completedAt
) {
}
