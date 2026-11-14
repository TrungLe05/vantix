package com.dev.payment_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.payment_service.service.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments/webhooks")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService webhookService;

    // Nhận body dạng String: chữ ký Stripe tính trên đúng chuỗi byte gốc, parse rồi serialize lại sẽ lệch
    @PostMapping("/stripe")
    public ApiResponse<Void> handleStripeEvent(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature
    ) {
        webhookService.handle(payload, signature);
        return ApiResponse.<Void>builder().build();
    }
}
