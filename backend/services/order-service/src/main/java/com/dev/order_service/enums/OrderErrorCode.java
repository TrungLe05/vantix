package com.dev.order_service.enums;

import com.dev.commonlib_api_response.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(4001, "Không tìm thấy đơn hàng", HttpStatus.NOT_FOUND),
    EVENT_NOT_AVAILABLE(4002, "Sự kiện hiện không mở bán", HttpStatus.CONFLICT),
    TICKET_NOT_AVAILABLE(4003, "Vé hoặc ghế không còn khả dụng", HttpStatus.CONFLICT),
    HOLD_EXPIRED(4004, "Thời gian giữ vé đã hết", HttpStatus.CONFLICT),
    ORDER_STATE_CONFLICT(4005, "Trạng thái đơn hàng không cho phép thao tác này", HttpStatus.CONFLICT),
    CART_ITEMS_UNAVAILABLE(4006, "Một hoặc nhiều lựa chọn không còn khả dụng", HttpStatus.CONFLICT),
    INVALID_ORDER_ITEMS(4007, "Danh sách vé không hợp lệ", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_CONFLICT(4008, "Idempotency-Key đã được dùng với nội dung khác", HttpStatus.CONFLICT),
    EVENT_INVENTORY_NOT_READY(4009, "Tồn kho sự kiện chưa sẵn sàng", HttpStatus.SERVICE_UNAVAILABLE),
    PRODUCT_SERVICE_UNAVAILABLE(4010, "Không thể xác minh thông tin sự kiện", HttpStatus.SERVICE_UNAVAILABLE),
    IDEMPOTENCY_KEY_REQUIRED(4011, "Thiếu Idempotency-Key", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
