package com.dev.order_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class AvailabilityResponse {
    private UUID eventId;
    private List<SeatAvailability> seats;
    private List<TicketTypeAvailability> ticketTypes;

    @Getter
    @Builder
    public static class SeatAvailability {
        private UUID seatId;
        private String status;
    }

    @Getter
    @Builder
    public static class TicketTypeAvailability {
        private UUID ticketTypeId;
        private String category;
        private int totalQuantity;
        private int availableQuantity;
    }
}
