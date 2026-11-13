package com.dev.order_service.repository;

import com.dev.order_service.entity.Order;
import com.dev.order_service.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.QueryHints;
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

    // SKIP LOCKED (lock timeout -2 của Hibernate): Order đang bị request khác khóa (đọc chi tiết,
    // availability, hủy event...) sẽ do chính request đó expire — job bỏ qua thay vì chờ, nên không
    // tạo vòng chờ khóa với các đường khóa nhiều Order theo thứ tự khác.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select o from Order o
            where o.status in :statuses and o.holdExpiresAt <= :now
            order by o.holdExpiresAt
            """)
    List<Order> findExpiredForUpdate(
            @Param("statuses") List<OrderStatus> statuses,
            @Param("now") Instant now
    );

    // Chỉ lấy id (không nạp entity vào persistence context): caller khóa từng Order bằng
    // findByIdForUpdate rồi kiểm tra lại trạng thái. Nạp entity trước rồi mới khóa sẽ giữ bản cũ
    // trong context, update sau đó lệch version khi luồng khác vừa expire/hủy cùng Order.
    @Query("""
            select o.id from Order o
            where o.buyerId = :buyerId and o.status in :statuses
              and (o.holdExpiresAt is null or o.holdExpiresAt <= :now)
            """)
    List<UUID> findExpiredIdsByBuyer(
            @Param("buyerId") UUID buyerId,
            @Param("statuses") List<OrderStatus> statuses,
            @Param("now") Instant now
    );

    @Query("select o.id from Order o where o.eventId = :eventId and o.status in :statuses")
    List<UUID> findIdsByEventIdAndStatusIn(
            @Param("eventId") UUID eventId,
            @Param("statuses") List<OrderStatus> statuses
    );
}

