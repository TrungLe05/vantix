package com.dev.order_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Kết quả quét vé. Cũng là {@code result} của lỗi 4017 (đã check-in) để STAFF biết lần quét trước:
 * ai quét, lúc nào.
 */
@Getter
@Builder
public class CheckInResponse {
    private UUID ticketId;
    private UUID orderId;
    private String ticketName;
    private String seatLabel;
    private Instant checkedInAt;
    private UUID checkedInBy;
}
