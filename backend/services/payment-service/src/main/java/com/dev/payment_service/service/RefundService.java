package com.dev.payment_service.service;

import com.dev.commonlib_kafka.event.RefundRequestedEvent;
import com.dev.payment_service.enums.RefundStatus;
import com.dev.payment_service.repository.RefundRepository;
import com.dev.payment_service.stripe.StripeGateway;
import com.dev.payment_service.stripe.StripeGatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Thực thi hoàn tiền theo yêu cầu của Order. Payment không tính % hoàn (D07, D16) — chỉ kiểm tra
 * số tiền không vượt khoản đã thu rồi gọi Stripe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final PaymentLedgerService ledger;
    private final RefundRepository refundRepository;
    private final StripeGateway stripeGateway;

    @Value("${app.payment.refund-retry-min-age:PT30S}")
    private Duration retryMinAge;

    public void handleRefundRequested(RefundRequestedEvent event) {
        ledger.registerRefund(event).ifPresent(this::execute);
    }

    /**
     * Thử lại refund đã ghi nhận nhưng chưa gọi được Stripe (mạng lỗi, Stripe 5xx, service tắt giữa
     * chừng). An toàn nhờ idempotency key theo refundId. Không giới hạn số lần: đây là tiền nợ buyer,
     * bỏ cuộc âm thầm tệ hơn là thử mãi; log WARN mỗi lần lỗi làm tín hiệu theo dõi.
     */
    @Scheduled(initialDelayString = "${app.payment.refund-retry-initial-delay-ms:30000}",
            fixedDelayString = "${app.payment.refund-retry-interval-ms:30000}")
    public void retryUnsentRefunds() {
        for (UUID refundId : refundRepository.findUnsentIds(
                RefundStatus.PENDING, Instant.now().minus(retryMinAge), PageRequest.of(0, 20))) {
            ledger.loadRetry(refundId).ifPresent(this::execute);
        }
    }

    private void execute(PaymentLedgerService.RefundAttempt attempt) {
        try {
            StripeGateway.RefundResult result = stripeGateway.createRefund(
                    attempt.paymentIntentId(), attempt.amount(), attempt.refundId());
            ledger.applyRefundResult(
                    attempt.refundId(), result.stripeRefundId(), result.status(), result.failureReason());
        } catch (StripeGatewayException ex) {
            if (ex.isRetryable()) {
                log.warn("Chưa hoàn được refundId={} code={}; scheduler sẽ thử lại",
                        attempt.refundId(), ex.getCode());
            } else {
                ledger.applyRefundResult(attempt.refundId(), null, "failed", ex.getCode());
            }
        }
    }
}
