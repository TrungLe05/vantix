package com.dev.order_service.repository;

import com.dev.order_service.entity.OrderItem;
import com.dev.order_service.enums.OrderItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
    List<OrderItem> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
    Optional<OrderItem> findByIdAndOrderId(UUID id, UUID orderId);
    Optional<OrderItem> findByOrderIdAndTicketTypeIdAndItemStatusAndCategorySnapshot(
            UUID orderId, UUID ticketTypeId, OrderItemStatus itemStatus, String categorySnapshot
    );
    List<OrderItem> findByOrderIdAndItemStatusOrderByCreatedAtAsc(UUID orderId, OrderItemStatus itemStatus);
}

