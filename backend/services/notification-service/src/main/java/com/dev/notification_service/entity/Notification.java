package com.dev.notification_service.entity;

import com.dev.notification_service.enums.EmailStatus;
import com.dev.notification_service.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/** Dòng được tạo bằng INSERT ... ON CONFLICT (NotificationRecorder); JPA dùng để đọc và đánh dấu đã đọc. */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Notification {

    @Id
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(length = 300)
    private String link;

    private UUID orderId;

    @Column(nullable = false, length = 150, updatable = false)
    private String dedupKey;

    private Instant readAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailStatus emailStatus;

    @Column(nullable = false)
    private int emailAttempts;

    private Instant nextEmailAttemptAt;

    @Column(length = 200)
    private String emailLastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
