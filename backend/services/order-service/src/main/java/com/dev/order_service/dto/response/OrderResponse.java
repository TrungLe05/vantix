package com.dev.order_service.dto.response;

import com.dev.order_service.enums.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class OrderResponse {
    private UUID id;
    private UUID eventId;
    private OrderStatus status;
    private String currency;
    private BigDecimal totalAmount;
    private Instant holdExpiresAt;
    private Instant paidAt;
    // null nếu Order chưa từng có yêu cầu hoàn tiền
    private RefundResponse refund;
    private List<OrderItemResponse> items;
}
