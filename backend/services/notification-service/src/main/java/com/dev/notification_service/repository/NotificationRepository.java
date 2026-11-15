package com.dev.notification_service.repository;

import com.dev.notification_service.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    @Query(value = """
            SELECT id FROM notifications
            WHERE email_status IN ('PENDING', 'FAILED')
              AND next_email_attempt_at IS NOT NULL AND next_email_attempt_at <= now()
            ORDER BY next_email_attempt_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDueEmailIds(@Param("limit") int limit);

    /**
     * Giành quyền gửi email của một dòng: đẩy lần thử kế tiếp ra xa để instance khác (hoặc lần poll
     * sau) không gửi trùng trong lúc dòng này đang được gửi. Trả 1 nếu giành được.
     */
    @Modifying
    @Query(value = """
            UPDATE notifications SET next_email_attempt_at = now() + interval '5 minutes'
            WHERE id = :id AND email_status IN ('PENDING', 'FAILED')
              AND next_email_attempt_at IS NOT NULL AND next_email_attempt_at <= now()
            """, nativeQuery = true)
    int claimEmail(@Param("id") UUID id);
}
