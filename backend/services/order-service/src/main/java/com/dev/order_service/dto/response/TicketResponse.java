package com.dev.order_service.dto.response;

import com.dev.order_service.enums.TicketStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class TicketResponse {
    private UUID id;
    private UUID ticketTypeId;
    private String ticketName;
    private String seatLabel;
    // Chỉ trả cho chính buyer (OR03): mã nhập tay khi không quét được QR
    private String code;
    private TicketStatus status;
    private Instant checkedInAt;
}
