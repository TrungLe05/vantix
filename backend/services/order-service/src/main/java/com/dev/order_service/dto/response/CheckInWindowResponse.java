package com.dev.order_service.dto.response;

import java.time.Instant;

/** Dữ liệu kèm lỗi 4021: STAFF biết khi nào mở/đóng quét vé. */
public record CheckInWindowResponse(Instant opensAt, Instant closesAt) {
}
