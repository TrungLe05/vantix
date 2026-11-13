package com.dev.order_service.repository;

import com.dev.order_service.entity.Hold;
import com.dev.order_service.enums.HoldStatus;
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
public interface HoldRepository extends JpaRepository<Hold, UUID> {

    List<Hold> findByOrderId(UUID orderId);

    Optional<Hold> findByOrderItemId(UUID orderItemId);

    Optional<Hold> findBySeatIdAndStatusIn(UUID seatId, List<HoldStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.orderId = :orderId and h.status in :statuses")
    List<Hold> findActiveByOrderIdForUpdate(
            @Param("orderId") UUID orderId,
            @Param("statuses") List<HoldStatus> statuses
    );

    @Query("select h.id from Hold h where h.status in :statuses and h.expiresAt <= :now")
    List<UUID> findExpiredIds(@Param("statuses") List<HoldStatus> statuses, @Param("now") Instant now);

    List<Hold> findBySeatIdInAndStatusIn(List<UUID> seatIds, List<HoldStatus> statuses);

    List<Hold> findBySeatIdInAndStatusInAndExpiresAtAfter(
            List<UUID> seatIds, List<HoldStatus> statuses, Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select h from Hold h
            where h.ticketTypeId = :ticketTypeId
              and h.status in :statuses
              and h.expiresAt <= :now
            """)
    List<Hold> findExpiredByTicketTypeIdForUpdate(
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("statuses") List<HoldStatus> statuses,
            @Param("now") Instant now
    );
}

