package com.dev.product_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response nhẹ dùng cho danh sách sự kiện (PR07).
 * Chỉ bao gồm các field cần hiển thị trên card/danh sách — không kèm description, ticketTypes đầy đủ.
 */
@Getter
@Builder
@AllArgsConstructor
public class EventSummaryResponse {

    private UUID id;
    private String name;
    private Instant startAt;
    private Instant endAt;
    private String status;
    private String bannerImageUrl;

    /** Giá thấp nhất trong các TicketType của event; null nếu chưa có TicketType (không xảy ra với PUBLISHED). */
    private BigDecimal minPrice;

    private VenueSummary venue;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class VenueSummary {
        private UUID id;
        private String name;
        private String city;
    }
}
