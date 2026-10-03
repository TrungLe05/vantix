package com.dev.product_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response đầy đủ cho chi tiết sự kiện (PR08).
 * Bao gồm venue (đầy đủ address), toàn bộ ticketTypes, và cờ hasSeatMap.
 */
@Getter
@Builder
@AllArgsConstructor
public class EventDetailResponse {

    private UUID id;
    private String name;
    private String description;
    private Instant startAt;
    private Instant endAt;
    private Instant salesStartAt;
    private Instant salesEndAt;
    private String status;
    private String bannerImageUrl;
    private Instant createdAt;

    private VenueDetail venue;
    private List<TicketTypeSummary> ticketTypes;

    /** true nếu event có SeatMap (tức là event có vé loại SEATED). */
    private boolean hasSeatMap;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class VenueDetail {
        private UUID id;
        private String name;
        private String address;
        private String city;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class TicketTypeSummary {
        private UUID id;
        private String category;
        private String name;
        private BigDecimal price;
        private int totalQuantity;
    }
}
