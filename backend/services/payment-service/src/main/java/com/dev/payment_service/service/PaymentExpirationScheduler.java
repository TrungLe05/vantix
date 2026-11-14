package com.dev.payment_service.service;

import com.dev.payment_service.entity.Payment;
import com.dev.payment_service.enums.PaymentStatus;
import com.dev.payment_service.repository.PaymentRepository;
import com.dev.payment_service.stripe.StripeGateway;
import com.dev.payment_service.stripe.StripeGatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Đồng bộ deadline Order ↔ Payment: khi hold của Order hết hạn, expire phiên Stripe để buyer không
 * trả tiền cho vé đã nhả. Stripe Checkout không cho đặt hạn dưới 30 phút nên phải chủ động expire.
 * Nếu Stripe báo phiên đã thanh toán (buyer trả sát deadline) thì ghi nhận thành công — đây cũng là
 * lưới an toàn khi webhook đến trễ hoặc thất lạc.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentExpirationScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentLedgerService ledger;
    private final StripeGateway stripeGateway;

    @Scheduled(initialDelayString = "${app.payment.expiration-initial-delay-ms:5000}",
            fixedDelayString = "${app.payment.expiration-poll-interval-ms:5000}")
    public void expireDuePayments() {
        for (UUID paymentId : paymentRepository.findIdsByStatusAndExpiresAtBefore(
                PaymentStatus.PENDING, Instant.now(), PageRequest.of(0, 50))) {
            try {
                expire(paymentId);
            } catch (StripeGatewayException ex) {
                // Stripe lỗi tạm thời: giữ PENDING, lần quét sau thử lại. Order vẫn tự expire theo TTL riêng.
                log.warn("Chưa expire được paymentId={} code={}", paymentId, ex.getCode());
            }
        }
    }

    private void expire(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        if (payment.getStripeSessionId() == null) {
            // Chưa từng tạo được phiên Stripe: không có gì để expire phía Stripe
            ledger.close(paymentId, PaymentStatus.EXPIRED);
            return;
        }
        StripeGateway.SessionState state = stripeGateway.expireSession(payment.getStripeSessionId());
        if (state.isPaid()) {
            ledger.markSucceeded(paymentId, state.paymentIntentId());
        } else if (state.isExpired()) {
            ledger.close(paymentId, PaymentStatus.EXPIRED);
        } else {
            // complete nhưng chưa paid (phương thức trả chậm): chờ webhook async_payment_*
            log.warn("Phiên Stripe chưa đóng được paymentId={} status={} paymentStatus={}",
                    paymentId, state.status(), state.paymentStatus());
        }
    }
}
