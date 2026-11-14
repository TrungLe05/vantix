package com.dev.order_service.enums;

public enum OrderStatus {
    CART,
    PENDING_PAYMENT,
    PAID,
    TICKETS_ISSUED,
    CANCELLED,
    EXPIRED,
    // P4: đã thanh toán, đang chờ Payment hoàn tiền / đã hoàn xong
    REFUND_PENDING,
    REFUNDED
}

