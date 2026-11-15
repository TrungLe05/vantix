package com.dev.order_service.entity;

import com.dev.order_service.enums.BotFlagStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Dòng được tạo/gộp bằng SQL upsert trong BotFlagService; JPA chỉ dùng để đọc và gỡ chặn. */
@Entity
@Table(name = "bot_flags")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class BotFlag {

    @Id
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID buyerId;

    @Column(length = 64)
    private String deviceHash;

    private UUID orderId;

    private UUID eventId;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal score;

    @Column(nullable = false, length = 64)
    private String modelVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String features;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @ToString.Include
    private BotFlagStatus status;

    @Column(nullable = false)
    private int hitCount;

    @Column(nullable = false)
    private Instant firstFlaggedAt;

    @Column(nullable = false)
    private Instant lastFlaggedAt;

    private Instant clearedAt;

    private UUID clearedBy;

    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, insertable = false)
    private Instant updatedAt;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
