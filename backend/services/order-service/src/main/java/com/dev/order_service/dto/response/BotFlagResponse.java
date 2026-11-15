package com.dev.order_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class BotFlagResponse {
    private UUID id;
    private UUID buyerId;
    private String deviceHash;
    private UUID orderId;
    private UUID eventId;
    private BigDecimal score;
    private String modelVersion;
    private Map<String, Object> features;
    private String status;
    private int hitCount;
    private Instant firstFlaggedAt;
    private Instant lastFlaggedAt;
    private Instant clearedAt;
    private UUID clearedBy;
}
