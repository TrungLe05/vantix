package com.dev.order_service.dto.response;

import java.util.Map;
import java.util.UUID;

public record ValidatedCatalog(
        ProductCatalog event,
        Map<UUID, ProductCatalog.TicketType> ticketTypes,
        Map<UUID, ProductCatalog.Seat> seats
) {
}
