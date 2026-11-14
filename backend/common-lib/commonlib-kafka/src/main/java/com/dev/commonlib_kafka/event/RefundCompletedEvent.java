package com.dev.commonlib_kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code refund.completed}. Key = orderId.
 * Producer: payment-service. Consumer: order-service (REFUND_PENDING → REFUNDED).
 */
public record RefundCompletedEvent(
        UUID refundId,
        UUID orderId,
        BigDecimal amount,
        String currency,
        Instant completedAt
) {
}
