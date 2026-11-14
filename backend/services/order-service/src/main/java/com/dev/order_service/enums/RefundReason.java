package com.dev.order_service.enums;

public enum RefundReason {
    // Buyer tự hủy đơn đã thanh toán, % theo chính sách 3 mốc
    BUYER_REQUEST,
    // ADMIN hủy sự kiện: hoàn 100%
    EVENT_CANCELLED,
    // Tiền vào sau khi Order đã EXPIRED/CANCELLED (vé đã nhả): hoàn 100%
    LATE_PAYMENT
}
