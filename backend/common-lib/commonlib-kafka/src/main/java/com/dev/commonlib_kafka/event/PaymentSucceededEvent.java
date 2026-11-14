package com.dev.commonlib_kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code payment.succeeded}. Key = orderId.
 * Producer: payment-service (webhook Stripe đã xác nhận tiền vào).
 * Consumer: order-service (PENDING_PAYMENT → PAID; Order đã EXPIRED/CANCELLED → yêu cầu hoàn 100%).
 * Payment không biết chi tiết vé (D07) nên payload chỉ có tiền và id tham chiếu.
 */
public record PaymentSucceededEvent(
        UUID paymentId,
        UUID orderId,
        UUID buyerId,
        BigDecimal amount,
        String currency,
        Instant paidAt
) {
}
