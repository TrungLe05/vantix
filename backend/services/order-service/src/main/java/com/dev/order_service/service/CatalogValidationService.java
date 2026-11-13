package com.dev.order_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.client.ProductCatalogClient;
import com.dev.order_service.dto.request.OrderItemRequest;
import com.dev.order_service.dto.response.ProductCatalog;
import com.dev.order_service.dto.response.ValidatedCatalog;
import com.dev.order_service.entity.OrderItem;
import com.dev.order_service.enums.OrderErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogValidationService {

    private final ProductCatalogClient productCatalogClient;

    public ValidatedCatalog loadAndValidate(UUID eventId, List<OrderItemRequest> requests) {
        ValidatedCatalog catalog = loadCatalog(eventId);
        validateSaleWindow(catalog.event());
        validateItems(requests, catalog);
        return catalog;
    }

    public ValidatedCatalog loadCatalog(UUID eventId) {
        ProductCatalog event = productCatalogClient.getEvent(eventId);
        Map<UUID, ProductCatalog.TicketType> ticketTypes = new HashMap<>();
        for (ProductCatalog.TicketType type : event.ticketTypes()) {
            ticketTypes.put(type.id(), type);
        }

        Map<UUID, ProductCatalog.Seat> seats = new HashMap<>();
        if (event.hasSeatMap()) {
            ProductCatalog.SeatMap seatMap = productCatalogClient.getSeatMap(eventId);
            for (ProductCatalog.Seat seat : seatMap.seats()) {
                seats.put(seat.id(), seat);
            }
        }

        return new ValidatedCatalog(event, Map.copyOf(ticketTypes), Map.copyOf(seats));
    }

    public void validateSaleWindow(ProductCatalog event) {
        Instant now = Instant.now();
        if (!"PUBLISHED".equals(event.status())
                || (event.salesStartAt() != null && event.salesStartAt().isAfter(now))
                || (event.salesEndAt() != null && !event.salesEndAt().isAfter(now))) {
            throw new AppException(OrderErrorCode.EVENT_NOT_AVAILABLE);
        }
    }

    private void validateItems(List<OrderItemRequest> requests, ValidatedCatalog catalog) {
        Set<UUID> requestedSeatIds = new HashSet<>();
        for (OrderItemRequest request : requests) {
            ProductCatalog.TicketType type = catalog.ticketTypes().get(request.getTicketTypeId());
            if (type == null || type.price() == null || type.name() == null) {
                throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
            }

            if ("SEATED".equals(type.category())) {
                if (request.getSeatId() == null || request.getQuantity() != 1
                        || !requestedSeatIds.add(request.getSeatId())) {
                    throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
                }
                ProductCatalog.Seat seat = catalog.seats().get(request.getSeatId());
                if (seat == null || !"ACTIVE".equals(seat.status())
                        || seat.ticketType() == null
                        || !type.id().equals(seat.ticketType().id())) {
                    throw new AppException(OrderErrorCode.TICKET_NOT_AVAILABLE);
                }
            } else if ("GENERAL_ADMISSION".equals(type.category())) {
                if (request.getSeatId() != null || request.getQuantity() == null || request.getQuantity() <= 0) {
                    throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
                }
            } else {
                throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
            }
        }

    }

    public OrderItem toOrderItem(UUID orderId, OrderItemRequest request, ValidatedCatalog catalog) {
        ProductCatalog.TicketType type = catalog.ticketTypes().get(request.getTicketTypeId());
        ProductCatalog.Seat seat = request.getSeatId() == null ? null : catalog.seats().get(request.getSeatId());
        return OrderItem.builder()
                .orderId(orderId)
                .ticketTypeId(type.id())
                .seatId(request.getSeatId())
                .ticketNameSnapshot(type.name())
                .categorySnapshot(type.category())
                .unitPriceSnapshot(type.price())
                .quantity(request.getQuantity())
                .subtotal(type.price().multiply(java.math.BigDecimal.valueOf(request.getQuantity())))
                .seatLabelSnapshot(seat == null ? null : seat.section() + " · " + seat.displayLabel())
                .build();
    }
}
