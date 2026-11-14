package com.dev.commonlib_kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code order.refund-requested}. Key = orderId.
 * Producer: order-service — Order đã tính xong số tiền hoàn theo chính sách (D16).
 * Consumer: payment-service — chỉ thực thi hoàn qua Stripe, idempotent theo {@code refundId}.
 *
 * @param reason {@code BUYER_REQUEST}, {@code EVENT_CANCELLED} hoặc {@code LATE_PAYMENT}
 */
public record RefundRequestedEvent(
        UUID refundId,
        UUID orderId,
        UUID buyerId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant requestedAt
) {
}
