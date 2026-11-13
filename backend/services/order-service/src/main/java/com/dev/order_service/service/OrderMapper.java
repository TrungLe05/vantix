package com.dev.order_service.service;

import com.dev.order_service.dto.response.OrderItemResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.entity.Order;
import com.dev.order_service.entity.OrderItem;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderMapper {

    private final OrderItemRepository orderItemRepository;

    public OrderResponse toResponse(Order order) {
        List<OrderItem> allItems = orderItemRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        List<OrderItemResponse> items = allItems.stream()
                .filter(item -> !isActiveCart(order) || item.getItemStatus().name().equals("ACTIVE"))
                .map(item -> OrderItemResponse.builder()
                        .id(item.getId())
                        .ticketTypeId(item.getTicketTypeId())
                        .ticketName(item.getTicketNameSnapshot())
                        .category(item.getCategorySnapshot())
                        .seatId(item.getSeatId())
                        .seatLabel(item.getSeatLabelSnapshot())
                        .status(item.getItemStatus().name())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPriceSnapshot())
                        .subtotal(item.getSubtotal())
                        .build())
                .toList();

        return OrderResponse.builder()
                .id(order.getId())
                .eventId(order.getEventId())
                .status(order.getStatus())
                .currency(order.getCurrency())
                .totalAmount(order.getTotalAmount())
                .holdExpiresAt(order.getHoldExpiresAt())
                .items(items)
                .build();
    }

    public List<OrderResponse> toResponses(List<Order> orders) {
        return orders.stream().map(this::toResponse).toList();
    }

    private boolean isActiveCart(Order order) {
        return order.getStatus() == OrderStatus.CART || order.getStatus() == OrderStatus.PENDING_PAYMENT;
    }
}
