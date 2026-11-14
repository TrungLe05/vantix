package com.dev.payment_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.payment_service.dto.request.CreateCheckoutSessionRequest;
import com.dev.payment_service.dto.response.CheckoutSessionResponse;
import com.dev.payment_service.entity.Payment;
import com.dev.payment_service.enums.PaymentErrorCode;
import com.dev.payment_service.enums.PaymentStatus;
import com.dev.payment_service.stripe.StripeGateway;
import com.dev.payment_service.stripe.StripeGatewayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    // Stripe không nhận khoản thanh toán USD dưới 0.50
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.50");

    private final PaymentLedgerService ledger;
    private final StripeGateway stripeGateway;

    @Value("${app.payment.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Override
    public CheckoutSessionResponse createCheckoutSession(CreateCheckoutSessionRequest request) {
        if (!"USD".equals(request.getCurrency())
                || request.getAmount().scale() > 2
                || request.getAmount().compareTo(MIN_AMOUNT) < 0) {
            throw new AppException(PaymentErrorCode.PAYMENT_AMOUNT_INVALID);
        }
        if (!request.getExpiresAt().isAfter(Instant.now())) {
            throw new AppException(PaymentErrorCode.PAYMENT_DEADLINE_PASSED);
        }

        Payment payment = ledger.register(request);
        // Payment đã có cho Order này: phải cùng buyer, cùng số tiền (Order đóng băng ở PENDING_PAYMENT)
        if (!payment.getBuyerId().equals(request.getBuyerId())
                || payment.getAmount().compareTo(request.getAmount()) != 0) {
            throw new AppException(PaymentErrorCode.PAYMENT_STATE_CONFLICT);
        }
        requirePending(payment);

        if (payment.getStripeSessionId() == null) {
            // Gọi Stripe ngoài transaction DB. Idempotency key theo paymentId nên request lặp/song
            // song nhận cùng một phiên, không tạo hai phiên cho một Order.
            StripeGateway.CheckoutSession session;
            try {
                session = stripeGateway.createCheckoutSession(new StripeGateway.CreateSessionCommand(
                        payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getCurrency(),
                        orderUrl(payment, "success"), orderUrl(payment, "cancelled")
                ));
            } catch (StripeGatewayException ex) {
                log.error("Không tạo được phiên thanh toán paymentId={} orderId={} code={}",
                        payment.getId(), payment.getOrderId(), ex.getCode());
                throw new AppException(PaymentErrorCode.STRIPE_UNAVAILABLE);
            }
            payment = ledger.attachSession(payment.getId(), session.sessionId(), session.url());
            // Scheduler có thể đã expire payment trong lúc chờ Stripe: không trả link cho payment đã đóng
            requirePending(payment);
        }

        return CheckoutSessionResponse.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .status(payment.getStatus().name())
                .checkoutUrl(payment.getCheckoutUrl())
                .expiresAt(payment.getExpiresAt())
                .build();
    }

    private void requirePending(Payment payment) {
        if (payment.getStatus() == PaymentStatus.EXPIRED) {
            throw new AppException(PaymentErrorCode.PAYMENT_DEADLINE_PASSED);
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new AppException(PaymentErrorCode.PAYMENT_STATE_CONFLICT);
        }
    }

    private String orderUrl(Payment payment, String outcome) {
        String base = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        return base + "/orders/" + payment.getOrderId() + "?payment=" + outcome;
    }
}
