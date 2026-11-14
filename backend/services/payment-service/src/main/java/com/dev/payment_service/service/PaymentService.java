package com.dev.payment_service.service;

import com.dev.payment_service.dto.request.CreateCheckoutSessionRequest;
import com.dev.payment_service.dto.response.CheckoutSessionResponse;

public interface PaymentService {

    /** Tạo (hoặc trả lại) phiên Stripe Checkout cho một Order — idempotent theo orderId. */
    CheckoutSessionResponse createCheckoutSession(CreateCheckoutSessionRequest request);
}
