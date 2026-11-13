package com.dev.user_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class RefreshToken {

    @Id
    @GeneratedValue
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(nullable = false)
    private UUID familyId;

    // Chỉ lưu FK thô (userId), KHÔNG map @ManyToOne sang User —
    // refresh token luôn được truy vấn/ghi theo family_id hoặc token_hash,
    // không cần load cả User entity kèm theo (tránh lazy-loading không cần thiết ở luồng nóng: login/refresh)
    @Column(nullable = false)
    private UUID userId;

    @ToString.Include
    @Column(nullable = false, length = 64)
    private String tokenHash;

    private String deviceFingerprintHash; // nullable, chưa dùng ở P1

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant revokedAt; // nullable: null = còn hiệu lực

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
