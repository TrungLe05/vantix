package com.dev.payment_service.stripe;

import lombok.Getter;

/**
 * Lỗi khi gọi Stripe. {@code retryable = true}: lỗi mạng/5xx/rate limit/cấu hình — thử lại sau có
 * thể thành công; {@code false}: Stripe từ chối yêu cầu, thử lại cũng vô ích.
 * Chỉ mang mã lỗi ngắn, không mang message gốc của Stripe (có thể chứa dữ liệu thanh toán).
 */
@Getter
public class StripeGatewayException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public StripeGatewayException(String code, boolean retryable) {
        super("Stripe call failed: " + code);
        this.code = code;
        this.retryable = retryable;
    }
}
