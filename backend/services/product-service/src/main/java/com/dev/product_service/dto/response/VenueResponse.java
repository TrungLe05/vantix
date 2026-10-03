package com.dev.product_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
public class VenueResponse {
    private UUID id;
    private String name;
    private String address;
    private String city;
}