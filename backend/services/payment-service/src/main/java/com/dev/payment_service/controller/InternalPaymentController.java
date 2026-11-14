package com.dev.payment_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_api_response.exception.CommonErrorCode;
import com.dev.commonlib_security.security.SecurityUtils;
import com.dev.payment_service.dto.request.CreateCheckoutSessionRequest;
import com.dev.payment_service.dto.response.CheckoutSessionResponse;
import com.dev.payment_service.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API nội bộ cho order-service (Order → Payment đồng bộ, D06). Đường dẫn /api/internal/** KHÔNG có
 * route ở Gateway nên client bên ngoài không gọi được qua Gateway; order-service chuyển tiếp
 * X-User-Id/X-User-Role của buyer giống cách Gateway làm (Trust-The-Gateway, D03).
 */
@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    @PostMapping("/checkout-sessions")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<CheckoutSessionResponse> createCheckoutSession(
            @Valid @RequestBody CreateCheckoutSessionRequest request
    ) {
        // Buyer chỉ được khởi tạo thanh toán cho chính mình
        if (!request.getBuyerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new AppException(CommonErrorCode.ACCESS_DENIED);
        }
        return ApiResponse.<CheckoutSessionResponse>builder()
                .result(paymentService.createCheckoutSession(request))
                .build();
    }
}
