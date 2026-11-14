package com.dev.commonlib_kafka.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload của topic {@code refund.failed}. Key = orderId.
 * Producer: payment-service. Consumer: order-service (đánh dấu refund FAILED để xử lý tay).
 *
 * @param reason mã lỗi ngắn (vd mã lỗi Stripe), không chứa dữ liệu thẻ hay message tự do
 */
public record RefundFailedEvent(
        UUID refundId,
        UUID orderId,
        String reason,
        Instant failedAt
) {
}
