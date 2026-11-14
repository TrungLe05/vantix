package com.dev.order_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class CheckoutResponse {
    private UUID orderId;
    // Trang thanh toán do Stripe host; frontend chuyển hướng buyer tới đây
    private String checkoutUrl;
    private Instant expiresAt;
}
