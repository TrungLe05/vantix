package com.dev.order_service.service;

import com.dev.order_service.dto.response.OrderItemResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.RefundResponse;
import com.dev.order_service.entity.Order;
import com.dev.order_service.entity.OrderItem;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.repository.OrderItemRepository;
import com.dev.order_service.repository.OrderRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderMapper {

    private final OrderItemRepository orderItemRepository;
    private final OrderRefundRepository orderRefundRepository;
    private final TicketService ticketService;

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
                .paidAt(order.getPaidAt())
                .refund(toRefundResponse(order))
                // Giỏ chưa thanh toán không có vé: bỏ truy vấn cho đường đọc nóng
                .tickets(isActiveCart(order) ? List.of() : ticketService.getTickets(order.getId()))
                .items(items)
                .build();
    }

    private RefundResponse toRefundResponse(Order order) {
        // Giỏ chưa thanh toán không thể có refund: bỏ một truy vấn cho đường đọc nóng nhất
        if (isActiveCart(order)) {
            return null;
        }
        return orderRefundRepository.findByOrderId(order.getId())
                .map(refund -> RefundResponse.builder()
                        .status(refund.getStatus())
                        .reason(refund.getReason())
                        .percent(refund.getRefundPercent())
                        .amount(refund.getAmount())
                        .requestedAt(refund.getCreatedAt())
                        .completedAt(refund.getCompletedAt())
                        .build())
                .orElse(null);
    }

    public List<OrderResponse> toResponses(List<Order> orders) {
        return orders.stream().map(this::toResponse).toList();
    }

    private boolean isActiveCart(Order order) {
        return order.getStatus() == OrderStatus.CART || order.getStatus() == OrderStatus.PENDING_PAYMENT;
    }
}
