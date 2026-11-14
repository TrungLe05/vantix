package com.dev.payment_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class CheckoutSessionResponse {
    private UUID paymentId;
    private UUID orderId;
    private String status;
    private String checkoutUrl;
    private Instant expiresAt;
}
