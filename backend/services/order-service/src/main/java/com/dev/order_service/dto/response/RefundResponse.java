package com.dev.order_service.dto.response;

import com.dev.order_service.enums.RefundReason;
import com.dev.order_service.enums.RefundStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Builder
public class RefundResponse {
    private RefundStatus status;
    private RefundReason reason;
    private int percent;
    private BigDecimal amount;
    private Instant requestedAt;
    private Instant completedAt;
}
