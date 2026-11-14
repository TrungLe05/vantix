package com.dev.order_service.client;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@FeignClient(name = "payment-service", url = "${app.payment-service.base-url}")
public interface PaymentFeignClient {

    // Chuyển tiếp danh tính buyer như Gateway làm: payment-service cũng dùng GatewayHeaderAuthFilter
    @PostMapping("/internal/payments/checkout-sessions")
    ApiResponse<CheckoutSession> createCheckoutSession(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") String userRole,
            @RequestBody CheckoutSessionRequest request
    );

    record CheckoutSessionRequest(UUID orderId, UUID buyerId, BigDecimal amount, String currency, Instant expiresAt) {
    }

    record CheckoutSession(UUID paymentId, UUID orderId, String status, String checkoutUrl, Instant expiresAt) {
    }
}
