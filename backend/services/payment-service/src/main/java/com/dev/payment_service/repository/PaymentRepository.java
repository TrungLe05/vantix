package com.dev.payment_service.repository;

import com.dev.payment_service.entity.Payment;
import com.dev.payment_service.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.stripeSessionId = :sessionId")
    Optional<Payment> findByStripeSessionIdForUpdate(@Param("sessionId") String sessionId);

    // ON CONFLICT trên uq_payments_order_id: hai request khởi tạo song song cho cùng Order chỉ tạo một dòng
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO payments (order_id, buyer_id, amount, currency, status, expires_at)
            VALUES (:orderId, :buyerId, :amount, :currency, 'PENDING', :expiresAt)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("orderId") UUID orderId,
            @Param("buyerId") UUID buyerId,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("expiresAt") Instant expiresAt
    );

    @Query("select p.id from Payment p where p.status = :status and p.expiresAt <= :now order by p.expiresAt")
    List<UUID> findIdsByStatusAndExpiresAtBefore(
            @Param("status") PaymentStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );
}
