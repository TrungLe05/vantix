package com.dev.payment_service.service;

import com.dev.commonlib_kafka.event.PaymentFailedEvent;
import com.dev.commonlib_kafka.event.PaymentSucceededEvent;
import com.dev.commonlib_kafka.event.RefundCompletedEvent;
import com.dev.commonlib_kafka.event.RefundFailedEvent;
import com.dev.commonlib_kafka.event.RefundRequestedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.payment_service.dto.request.CreateCheckoutSessionRequest;
import com.dev.payment_service.entity.Payment;
import com.dev.payment_service.entity.Refund;
import com.dev.payment_service.enums.PaymentStatus;
import com.dev.payment_service.enums.RefundStatus;
import com.dev.payment_service.event.PaymentEventPublisher;
import com.dev.payment_service.repository.PaymentRepository;
import com.dev.payment_service.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Mọi thay đổi trạng thái payment/refund trong DB, mỗi method là một transaction ngắn và ghi event
 * outbox cùng transaction. Lời gọi Stripe (chậm, có thể lỗi) luôn nằm NGOÀI các transaction này,
 * do {@link PaymentServiceImpl}, {@link RefundService} và các scheduler điều phối.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLedgerService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    // ---------- payment ----------

    /** Tạo payment PENDING cho Order nếu chưa có, trả về dòng hiện tại (mới hoặc đã tồn tại). */
    @Transactional
    public Payment register(CreateCheckoutSessionRequest request) {
        paymentRepository.insertIfAbsent(
                request.getOrderId(), request.getBuyerId(), request.getAmount(),
                request.getCurrency(), request.getExpiresAt()
        );
        return paymentRepository.findByOrderId(request.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Payment vừa tạo không đọc lại được"));
    }

    @Transactional
    public Payment attachSession(UUID paymentId, String sessionId, String checkoutUrl) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment không tồn tại"));
        // Request song song dùng cùng idempotency key nên Stripe trả cùng một phiên; chỉ ghi lần đầu
        if (payment.getStripeSessionId() == null) {
            payment.setStripeSessionId(sessionId);
            payment.setCheckoutUrl(checkoutUrl);
        }
        return payment;
    }

    @Transactional
    public void markSucceeded(UUID paymentId, String paymentIntentId) {
        paymentRepository.findByIdForUpdate(paymentId).ifPresent(payment -> succeed(payment, paymentIntentId));
    }

    /** Đóng payment còn PENDING (EXPIRED/FAILED) và báo Order nhả hold. */
    @Transactional
    public void close(UUID paymentId, PaymentStatus target) {
        paymentRepository.findByIdForUpdate(paymentId).ifPresent(payment -> close(payment, target));
    }

    /**
     * Xử lý một webhook Stripe đã xác thực chữ ký. Event id được ghi cùng transaction với thay đổi
     * trạng thái: Stripe gửi lại → không xử lý lần hai; xử lý lỗi → rollback cả event id để lần gửi
     * lại của Stripe được xử lý.
     *
     * @return false nếu event đã được xử lý trước đó
     */
    @Transactional
    public boolean applyWebhookEvent(String eventId, String type, JsonNode object) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO stripe_webhook_events (event_id, event_type) VALUES (?, ?) " +
                        "ON CONFLICT (event_id) DO NOTHING",
                eventId, type
        );
        if (inserted == 0) {
            return false;
        }
        switch (type) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> {
                // completed với payment_status=unpaid là phương thức trả chậm: chờ async_payment_succeeded
                if ("paid".equals(text(object, "payment_status"))) {
                    findBySession(object).ifPresent(payment -> succeed(payment, text(object, "payment_intent")));
                }
            }
            case "checkout.session.expired" ->
                    findBySession(object).ifPresent(payment -> close(payment, PaymentStatus.EXPIRED));
            case "checkout.session.async_payment_failed" ->
                    findBySession(object).ifPresent(payment -> close(payment, PaymentStatus.FAILED));
            case "refund.updated", "refund.failed" -> applyRefundWebhook(object);
            default -> log.debug("Bỏ qua webhook Stripe type={}", type);
        }
        return true;
    }

    private Optional<Payment> findBySession(JsonNode session) {
        String sessionId = text(session, "id");
        Optional<Payment> payment = sessionId == null
                ? Optional.empty()
                : paymentRepository.findByStripeSessionIdForUpdate(sessionId);
        if (payment.isEmpty()) {
            // Tài khoản Stripe test có thể dùng chung với ứng dụng khác: không coi là lỗi
            log.warn("Webhook Stripe cho phiên không thuộc hệ thống, bỏ qua");
        }
        return payment;
    }

    private void succeed(Payment payment, String paymentIntentId) {
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            return;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            // Tiền đã vào dù payment đã đóng: vẫn ghi nhận và báo Order — Order sẽ yêu cầu hoàn 100%
            log.error("PAYMENT_SUCCEEDED_AFTER_CLOSE paymentId={} orderId={} previousStatus={}",
                    payment.getId(), payment.getOrderId(), payment.getStatus());
        }
        Instant now = Instant.now();
        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setStripePaymentIntentId(paymentIntentId);
        payment.setPaidAt(now);
        eventPublisher.record(KafkaTopics.PAYMENT_SUCCEEDED, payment.getOrderId(), new PaymentSucceededEvent(
                payment.getId(), payment.getOrderId(), payment.getBuyerId(),
                payment.getAmount(), payment.getCurrency(), now
        ));
        log.info("Payment thành công paymentId={} orderId={}", payment.getId(), payment.getOrderId());
    }

    private void close(Payment payment, PaymentStatus target) {
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        payment.setStatus(target);
        eventPublisher.record(KafkaTopics.PAYMENT_FAILED, payment.getOrderId(), new PaymentFailedEvent(
                payment.getId(), payment.getOrderId(), payment.getBuyerId(),
                target == PaymentStatus.EXPIRED ? PaymentFailedEvent.REASON_EXPIRED : PaymentFailedEvent.REASON_FAILED,
                Instant.now()
        ));
        log.info("Payment đóng paymentId={} orderId={} status={}", payment.getId(), payment.getOrderId(), target);
    }

    // ---------- refund ----------

    public record RefundAttempt(UUID refundId, String paymentIntentId, BigDecimal amount) {
    }

    /**
     * Ghi nhận yêu cầu hoàn từ Order, idempotent theo refundId.
     *
     * @return thông tin để gọi Stripe; rỗng nếu yêu cầu đã xử lý xong hoặc bị từ chối (đã phát refund.failed)
     */
    @Transactional
    public Optional<RefundAttempt> registerRefund(RefundRequestedEvent event) {
        // Khóa payment để hai yêu cầu hoàn của cùng payment không cùng vượt tổng tiền đã thu
        Payment payment = paymentRepository.findByOrderIdForUpdate(event.orderId()).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.SUCCEEDED
                || payment.getStripePaymentIntentId() == null) {
            log.error("REFUND_REJECTED refundId={} orderId={} reason=payment_not_refundable",
                    event.refundId(), event.orderId());
            publishRefundFailed(event.refundId(), event.orderId(), "payment_not_refundable");
            return Optional.empty();
        }

        Refund existing = refundRepository.findByIdForUpdate(event.refundId()).orElse(null);
        if (existing != null) {
            return retryable(existing, payment);
        }

        BigDecimal alreadyRefunded = refundRepository.sumAmountByPaymentIdExcluding(
                payment.getId(), RefundStatus.FAILED, event.refundId());
        boolean exceeds = event.amount() == null || event.amount().signum() <= 0
                || alreadyRefunded.add(event.amount()).compareTo(payment.getAmount()) > 0;
        if (exceeds) {
            log.error("REFUND_REJECTED refundId={} orderId={} reason=amount_exceeds_payment",
                    event.refundId(), event.orderId());
            publishRefundFailed(event.refundId(), event.orderId(), "amount_exceeds_payment");
            return Optional.empty();
        }

        Refund refund = refundRepository.saveAndFlush(Refund.builder()
                .id(event.refundId())
                .paymentId(payment.getId())
                .orderId(event.orderId())
                .amount(event.amount())
                .currency(payment.getCurrency())
                .reason(event.reason())
                .status(RefundStatus.PENDING)
                .build());
        return Optional.of(new RefundAttempt(refund.getId(), payment.getStripePaymentIntentId(), refund.getAmount()));
    }

    /** Refund PENDING chưa gọi được Stripe → trả thông tin để thử lại. */
    @Transactional(readOnly = true)
    public Optional<RefundAttempt> loadRetry(UUID refundId) {
        return refundRepository.findById(refundId).flatMap(refund ->
                paymentRepository.findById(refund.getPaymentId()).flatMap(payment -> retryable(refund, payment)));
    }

    private Optional<RefundAttempt> retryable(Refund refund, Payment payment) {
        if (refund.getStatus() != RefundStatus.PENDING || refund.getStripeRefundId() != null) {
            return Optional.empty();
        }
        return Optional.of(new RefundAttempt(refund.getId(), payment.getStripePaymentIntentId(), refund.getAmount()));
    }

    /** Áp kết quả Stripe trả về (đồng bộ hoặc qua webhook) vào refund còn PENDING. */
    @Transactional
    public void applyRefundResult(UUID refundId, String stripeRefundId, String stripeStatus, String failureReason) {
        refundRepository.findByIdForUpdate(refundId)
                .ifPresent(refund -> settle(refund, stripeRefundId, stripeStatus, failureReason));
    }

    private void applyRefundWebhook(JsonNode object) {
        String stripeRefundId = text(object, "id");
        Optional<Refund> refund = stripeRefundId == null
                ? Optional.empty()
                : refundRepository.findByStripeRefundId(stripeRefundId);
        if (refund.isEmpty()) {
            // Webhook có thể tới trước khi ta kịp lưu stripe_refund_id: tìm theo metadata do ta gắn
            refund = parseUuid(text(object.path("metadata"), "refundId")).flatMap(refundRepository::findById);
        }
        refund.flatMap(found -> refundRepository.findByIdForUpdate(found.getId()))
                .ifPresent(locked -> settle(locked, stripeRefundId,
                        text(object, "status"), text(object, "failure_reason")));
    }

    private void settle(Refund refund, String stripeRefundId, String stripeStatus, String failureReason) {
        if (refund.getStatus() != RefundStatus.PENDING) {
            return;
        }
        if (stripeRefundId != null) {
            refund.setStripeRefundId(stripeRefundId);
        }
        if ("succeeded".equals(stripeStatus)) {
            refund.setStatus(RefundStatus.SUCCEEDED);
            eventPublisher.record(KafkaTopics.REFUND_COMPLETED, refund.getOrderId(), new RefundCompletedEvent(
                    refund.getId(), refund.getOrderId(), refund.getAmount(), refund.getCurrency(), Instant.now()
            ));
            log.info("Refund hoàn tất refundId={} orderId={}", refund.getId(), refund.getOrderId());
        } else if ("failed".equals(stripeStatus) || "canceled".equals(stripeStatus)) {
            String reason = truncate(failureReason != null ? failureReason : stripeStatus);
            refund.setStatus(RefundStatus.FAILED);
            refund.setFailureReason(reason);
            publishRefundFailed(refund.getId(), refund.getOrderId(), reason);
            log.error("REFUND_FAILED refundId={} orderId={} reason={}", refund.getId(), refund.getOrderId(), reason);
        }
        // pending / requires_action: giữ PENDING, webhook refund.updated sẽ chốt
    }

    private void publishRefundFailed(UUID refundId, UUID orderId, String reason) {
        eventPublisher.record(KafkaTopics.REFUND_FAILED, orderId,
                new RefundFailedEvent(refundId, orderId, reason, Instant.now()));
    }

    // ---------- helper ----------

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String truncate(String value) {
        return value.length() <= 100 ? value : value.substring(0, 100);
    }
}
