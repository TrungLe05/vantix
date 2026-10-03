package com.dev.product_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
public class SeatMapResponse {
    private UUID id;
    private UUID eventId;
    private String name;
}