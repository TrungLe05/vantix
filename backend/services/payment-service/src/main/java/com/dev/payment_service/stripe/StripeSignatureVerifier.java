package com.dev.payment_service.stripe;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Xác thực header {@code Stripe-Signature} (HMAC-SHA256 trên "timestamp.payload") bằng signing secret
 * của endpoint. Thuần tính toán cục bộ, không gọi mạng.
 */
@Component
public class StripeSignatureVerifier {

    // Stripe khuyến nghị 5 phút: chặn replay webhook cũ mà vẫn chịu được lệch đồng hồ
    private static final long TOLERANCE_SECONDS = 300;

    private final String webhookSecret;

    public StripeSignatureVerifier(@Value("${app.payment.stripe.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public boolean isValid(String payload, String signatureHeader) {
        // Chưa cấu hình secret thì từ chối tất cả (fail closed), không chấp nhận webhook không kiểm chứng
        if (!StringUtils.hasText(webhookSecret) || !StringUtils.hasText(signatureHeader) || payload == null) {
            return false;
        }
        try {
            return Webhook.Signature.verifyHeader(payload, signatureHeader, webhookSecret, TOLERANCE_SECONDS);
        } catch (SignatureVerificationException ex) {
            return false;
        }
    }
}
