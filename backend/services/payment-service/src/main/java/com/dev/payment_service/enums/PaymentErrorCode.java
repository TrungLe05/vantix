package com.dev.payment_service.enums;

import com.dev.commonlib_api_response.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// Dải 5xxx: 50xx payment, 51xx Stripe, 52xx webhook
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    PAYMENT_STATE_CONFLICT(5002, "Thanh toán của đơn hàng không cho phép thao tác này", HttpStatus.CONFLICT),
    PAYMENT_AMOUNT_INVALID(5003, "Số tiền thanh toán không hợp lệ", HttpStatus.BAD_REQUEST),
    PAYMENT_DEADLINE_PASSED(5004, "Đã quá hạn thanh toán của đơn hàng", HttpStatus.CONFLICT),
    STRIPE_UNAVAILABLE(5101, "Cổng thanh toán tạm thời không khả dụng", HttpStatus.SERVICE_UNAVAILABLE),
    WEBHOOK_SIGNATURE_INVALID(5201, "Chữ ký webhook không hợp lệ", HttpStatus.BAD_REQUEST),
    WEBHOOK_PAYLOAD_INVALID(5202, "Nội dung webhook không hợp lệ", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
