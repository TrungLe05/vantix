package com.dev.order_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class OrderItemResponse {
    private UUID id;
    private UUID ticketTypeId;
    private String ticketName;
    private String category;
    private String status;
    private UUID seatId;
    private String seatLabel;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}
