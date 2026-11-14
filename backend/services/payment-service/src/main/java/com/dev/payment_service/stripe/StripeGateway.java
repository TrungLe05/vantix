package com.dev.payment_service.stripe;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ranh giới với Stripe. Service nghiệp vụ chỉ phụ thuộc interface này để integration test thay bằng
 * bản giả (không gọi mạng); bản thật là {@link StripeSdkGateway}.
 */
public interface StripeGateway {

    /** Tạo Checkout Session; gọi lại với cùng {@code paymentId} phải trả về cùng một phiên. */
    CheckoutSession createCheckoutSession(CreateSessionCommand command);

    /** Expire phiên nếu còn mở rồi trả trạng thái cuối — phiên đã thanh toán thì không expire được. */
    SessionState expireSession(String sessionId);

    /** Hoàn tiền; gọi lại với cùng {@code refundId} không tạo refund thứ hai. */
    RefundResult createRefund(String paymentIntentId, BigDecimal amount, UUID refundId);

    record CreateSessionCommand(
            UUID paymentId,
            UUID orderId,
            BigDecimal amount,
            String currency,
            String successUrl,
            String cancelUrl
    ) {
    }

    record CheckoutSession(String sessionId, String url) {
    }

    /**
     * @param status        {@code open}, {@code complete} hoặc {@code expired}
     * @param paymentStatus {@code paid}, {@code unpaid} hoặc {@code no_payment_required}
     */
    record SessionState(String status, String paymentStatus, String paymentIntentId) {
        public boolean isPaid() {
            return "complete".equals(status) && "paid".equals(paymentStatus);
        }

        public boolean isExpired() {
            return "expired".equals(status);
        }
    }

    /**
     * @param status {@code succeeded}, {@code pending}, {@code requires_action}, {@code failed}
     *               hoặc {@code canceled}
     */
    record RefundResult(String stripeRefundId, String status, String failureReason) {
    }
}
