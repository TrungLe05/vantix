package com.dev.product_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
public class SeatResponse {
    private UUID id;
    private String section;
    private String rowLabel;
    private String seatNumber;
    private String displayLabel;
}