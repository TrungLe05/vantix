package com.dev.commonlib_kafka.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code payment.failed}. Key = orderId.
 * Producer: payment-service. Consumer: order-service (nhả hold nếu Order còn PENDING_PAYMENT).
 *
 * @param reason {@code EXPIRED} (phiên thanh toán hết hạn) hoặc {@code FAILED} (Stripe báo thất bại)
 */
public record PaymentFailedEvent(
        UUID paymentId,
        UUID orderId,
        UUID buyerId,
        String reason,
        Instant failedAt
) {
    public static final String REASON_EXPIRED = "EXPIRED";
    public static final String REASON_FAILED = "FAILED";
}
