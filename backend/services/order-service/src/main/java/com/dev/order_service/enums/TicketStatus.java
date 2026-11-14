package com.dev.order_service.enums;

public enum TicketStatus {
    VALID,
    CHECKED_IN,
    // Vé bị hủy do hoàn tiền hoặc sự kiện bị hủy: không vào cổng được nữa
    VOID
}
