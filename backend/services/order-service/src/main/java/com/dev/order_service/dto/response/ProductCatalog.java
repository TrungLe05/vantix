package com.dev.order_service.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductCatalog(
        UUID id,
        String status,
        Instant salesStartAt,
        Instant salesEndAt,
        boolean hasSeatMap,
        List<TicketType> ticketTypes
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TicketType(UUID id, String category, String name, BigDecimal price, int totalQuantity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatMap(UUID seatMapId, List<Seat> seats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Seat(
            UUID id,
            String section,
            String rowLabel,
            String seatNumber,
            String displayLabel,
            String status,
            TicketBrief ticketType
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TicketBrief(UUID id, String name, BigDecimal price) {
    }
}
