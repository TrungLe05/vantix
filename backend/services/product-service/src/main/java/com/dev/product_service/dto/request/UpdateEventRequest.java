package com.dev.product_service.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class UpdateEventRequest {

    private UUID venueId;

    @Size(max = 200)
    private String name;

    @Size(max = 2000)
    private String description;

    private Instant startAt;

    private Instant endAt;

    private Instant salesStartAt;

    private Instant salesEndAt;

    @Size(max = 500)
    @Pattern(regexp = "^https?://.+", message = "bannerImageUrl phải là URL hợp lệ")
    private String bannerImageUrl;
}