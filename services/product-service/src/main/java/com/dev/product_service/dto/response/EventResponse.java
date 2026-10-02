package com.dev.product_service.dto.response;

import com.dev.product_service.enums.EventStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@AllArgsConstructor
public class EventResponse {
    private UUID id;
    private UUID venueId;
    private String name;
    private String description;
    private Instant startAt;
    private Instant endAt;
    private Instant salesStartAt;
    private Instant salesEndAt;
    private EventStatus status;
    private String bannerImageUrl;
    private Instant createdAt;
}