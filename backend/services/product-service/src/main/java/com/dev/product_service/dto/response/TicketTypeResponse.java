package com.dev.product_service.dto.response;

import com.dev.product_service.enums.TicketCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
public class TicketTypeResponse {
    private UUID id;
    private UUID eventId;
    private TicketCategory category;
    private String name;
    private BigDecimal price;
    private int totalQuantity;
}