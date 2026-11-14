package com.dev.payment_service.repository;

import com.dev.payment_service.entity.Refund;
import com.dev.payment_service.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") UUID id);

    Optional<Refund> findByStripeRefundId(String stripeRefundId);

    // Tổng đã/đang hoàn của một payment (không tính refund FAILED và refund đang xét)
    @Query("""
            select coalesce(sum(r.amount), 0) from Refund r
            where r.paymentId = :paymentId and r.status <> :excluded and r.id <> :selfId
            """)
    BigDecimal sumAmountByPaymentIdExcluding(
            @Param("paymentId") UUID paymentId,
            @Param("excluded") RefundStatus excluded,
            @Param("selfId") UUID selfId
    );

    // Refund chưa gọi được Stripe (chưa có stripe_refund_id) để scheduler thử lại
    @Query("""
            select r.id from Refund r
            where r.status = :status and r.stripeRefundId is null and r.createdAt <= :before
            order by r.createdAt
            """)
    List<UUID> findUnsentIds(
            @Param("status") RefundStatus status,
            @Param("before") Instant before,
            Pageable pageable
    );
}
