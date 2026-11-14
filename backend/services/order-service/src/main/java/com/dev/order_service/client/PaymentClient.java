package com.dev.order_service.client;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.enums.OrderErrorCode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Order → Payment đồng bộ, chỉ để lấy link phiên thanh toán (D06). Lỗi nào cũng fail closed (4013). */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentClient {

    private final PaymentFeignClient paymentFeignClient;

    public PaymentFeignClient.CheckoutSession createCheckoutSession(
            UUID orderId, UUID buyerId, BigDecimal amount, String currency, Instant expiresAt
    ) {
        ApiResponse<PaymentFeignClient.CheckoutSession> response;
        try {
            response = paymentFeignClient.createCheckoutSession(
                    buyerId.toString(), "BUYER",
                    new PaymentFeignClient.CheckoutSessionRequest(orderId, buyerId, amount, currency, expiresAt)
            );
        } catch (FeignException ex) {
            // Không log body trả về: chỉ trạng thái HTTP và id nội bộ
            log.error("Payment-service request failed: operation=create-checkout-session, orderId={}, " +
                            "httpStatus={}, exceptionType={}",
                    orderId, ex.status() < 0 ? "no-response" : ex.status(), ex.getClass().getSimpleName());
            throw new AppException(OrderErrorCode.PAYMENT_SERVICE_UNAVAILABLE);
        }
        if (response == null || response.getResult() == null || response.getResult().checkoutUrl() == null) {
            throw new AppException(OrderErrorCode.PAYMENT_SERVICE_UNAVAILABLE);
        }
        return response.getResult();
    }
}
