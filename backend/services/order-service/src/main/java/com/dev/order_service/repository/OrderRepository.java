package com.dev.order_service.repository;

import com.dev.order_service.entity.Order;
import com.dev.order_service.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByBuyerIdAndEventIdAndStatus(UUID buyerId, UUID eventId, OrderStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.buyerId = :buyerId and o.eventId = :eventId and o.status = :status")
    Optional<Order> findCartForUpdate(
            @Param("buyerId") UUID buyerId,
            @Param("eventId") UUID eventId,
            @Param("status") OrderStatus status
    );

    List<Order> findByBuyerIdAndStatusInOrderByCreatedAtDesc(UUID buyerId, List<OrderStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId and o.buyerId = :buyerId")
    Optional<Order> findOwnedForUpdate(@Param("orderId") UUID orderId, @Param("buyerId") UUID buyerId);

    Optional<Order> findByIdAndBuyerId(UUID orderId, UUID buyerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from Order o
            where o.status in :statuses and o.holdExpiresAt <= :now
            order by o.holdExpiresAt
            """)
    List<Order> findExpiredForUpdate(
            @Param("statuses") List<OrderStatus> statuses,
            @Param("now") Instant now
    );

    List<Order> findByEventIdAndStatusIn(UUID eventId, List<OrderStatus> statuses);
}

