package com.dev.notification_service.service;

import com.dev.notification_service.dto.response.NotificationResponse;
import com.dev.notification_service.enums.NotificationType;
import com.dev.notification_service.websocket.NotificationPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ghi thông báo idempotent theo dedup_key. Email không gửi ở đây (EmailDispatchScheduler lo, có retry),
 * nên consumer Kafka không bị SMTP chậm chặn. WebSocket đẩy sau commit để client không nhận thông báo
 * mà rồi đọc lại API không thấy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationRecorder {

    private final JdbcTemplate jdbcTemplate;
    private final NotificationPushService pushService;

    /** @return true nếu tạo mới; false nếu bản tin này đã được ghi trước đó (nhận lặp) */
    @Transactional
    public boolean record(UUID userId, NotificationType type, NotificationContent.Content content,
                          UUID orderId, String dedupKey) {
        Instant now = Instant.now();
        List<UUID> inserted = jdbcTemplate.queryForList("""
                        INSERT INTO notifications
                            (id, user_id, type, title, body, link, order_id, dedup_key, email_status, created_at)
                        VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                        ON CONFLICT (dedup_key) DO NOTHING
                        RETURNING id
                        """, UUID.class,
                userId, type.name(), content.title(), content.body(), content.link(), orderId, dedupKey,
                Timestamp.from(now));
        if (inserted.isEmpty()) {
            log.debug("Bỏ qua thông báo lặp dedupKey={}", dedupKey);
            return false;
        }
        NotificationResponse response = NotificationResponse.builder()
                .id(inserted.get(0))
                .type(type)
                .title(content.title())
                .body(content.body())
                .link(content.link())
                .orderId(orderId)
                .read(false)
                .createdAt(now)
                .build();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushService.push(userId, response);
            }
        });
        return true;
    }
}
