package com.dev.payment_service.stripe;

import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionExpireParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Gọi Stripe qua stripe-java. Dùng {@link RequestOptions} cho từng request (không đặt
 * {@code Stripe.apiKey} static) để khóa không nằm ở trạng thái toàn cục.
 */
@Component
@Slf4j
public class StripeSdkGateway implements StripeGateway {

    private final String secretKey;

    public StripeSdkGateway(@Value("${app.payment.stripe.secret-key:}") String secretKey) {
        this.secretKey = secretKey;
    }

    @Override
    public CheckoutSession createCheckoutSession(CreateSessionCommand command) {
        // Không đặt expires_at: Stripe yêu cầu tối thiểu 30 phút (dài hơn hold 10 phút) và tham số phải
        // giống hệt giữa các lần retry cùng idempotency key. Deadline thật do
        // PaymentExpirationScheduler áp bằng cách expire phiên khi hold hết hạn.
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(command.successUrl())
                .setCancelUrl(command.cancelUrl())
                .setClientReferenceId(command.orderId().toString())
                .putMetadata("orderId", command.orderId().toString())
                .putMetadata("paymentId", command.paymentId().toString())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(command.currency().toLowerCase())
                                .setUnitAmount(toMinorUnits(command.amount()))
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        // Payment không biết vé (D07): chỉ hiển thị mã đơn
                                        .setName("Vantix order " + command.orderId().toString().substring(0, 8))
                                        .build())
                                .build())
                        .build())
                .build();
        try {
            Session session = Session.create(params, options("checkout-" + command.paymentId()));
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException ex) {
            throw translate("create-session", ex);
        }
    }

    @Override
    public SessionState expireSession(String sessionId) {
        try {
            Session session = Session.retrieve(sessionId, options(null));
            if ("open".equals(session.getStatus())) {
                try {
                    session = session.expire(SessionExpireParams.builder().build(), options(null));
                } catch (InvalidRequestException raced) {
                    // Buyer vừa thanh toán xong (hoặc phiên vừa hết hạn) giữa retrieve và expire
                    session = Session.retrieve(sessionId, options(null));
                }
            }
            return new SessionState(session.getStatus(), session.getPaymentStatus(), session.getPaymentIntent());
        } catch (StripeException ex) {
            throw translate("expire-session", ex);
        }
    }

    @Override
    public RefundResult createRefund(String paymentIntentId, BigDecimal amount, UUID refundId) {
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setAmount(toMinorUnits(amount))
                .putMetadata("refundId", refundId.toString())
                .build();
        try {
            Refund refund = Refund.create(params, options("refund-" + refundId));
            return new RefundResult(refund.getId(), refund.getStatus(), refund.getFailureReason());
        } catch (StripeException ex) {
            throw translate("create-refund", ex);
        }
    }

    private RequestOptions options(String idempotencyKey) {
        if (!StringUtils.hasText(secretKey)) {
            // Thiếu STRIPE_SECRET_KEY là lỗi cấu hình, sửa xong thì thử lại được
            throw new StripeGatewayException("stripe_not_configured", true);
        }
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder()
                .setApiKey(secretKey)
                .setConnectTimeout(3_000)
                .setReadTimeout(8_000)
                .setMaxNetworkRetries(1);
        if (idempotencyKey != null) {
            builder.setIdempotencyKey(idempotencyKey);
        }
        return builder.build();
    }

    private StripeGatewayException translate(String operation, StripeException ex) {
        // Stripe từ chối yêu cầu → thử lại vô ích; còn lại (mạng, 5xx, rate limit, sai khóa) coi là tạm thời
        boolean permanent = ex instanceof CardException
                || ex instanceof InvalidRequestException
                || ex instanceof IdempotencyException;
        String code = ex.getCode() != null ? ex.getCode() : ex.getClass().getSimpleName();
        // Không log message của Stripe: có thể chứa chi tiết thanh toán
        log.warn("Stripe {} thất bại: type={} code={} httpStatus={} retryable={}",
                operation, ex.getClass().getSimpleName(), code, ex.getStatusCode(), !permanent);
        return new StripeGatewayException(code, !permanent);
    }

    private static long toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }
}
