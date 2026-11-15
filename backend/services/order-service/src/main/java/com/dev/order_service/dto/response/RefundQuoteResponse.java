package com.dev.order_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Số tiền buyer sẽ nhận nếu yêu cầu hoàn ngay lúc này — để UI hiển thị trước khi xác nhận. */
@Getter
@Builder
public class RefundQuoteResponse {
    private UUID orderId;
    private boolean refundable;
    private int percent;
    private BigDecimal amount;
    private String currency;
    private BigDecimal paidAmount;
    private Instant eventStartAt;
    // D43: lý do khi refundable = false (NOT_PAID | ALREADY_REFUNDED | TOO_CLOSE_TO_EVENT | ALL_TICKETS_USED)
    private String reason;
    // Vé chưa dùng sẽ được hoàn / vé đã check-in (không hoàn)
    private int refundableTickets;
    private int usedTickets;
}
