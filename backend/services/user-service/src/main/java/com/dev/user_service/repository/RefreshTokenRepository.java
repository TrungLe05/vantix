package com.dev.user_service.repository;

import com.dev.user_service.entities.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Thu hồi 1 token cụ thể một cách NGUYÊN TỬ — dùng ở A08 bước 5:
    // affected rows = 0 nghĩa là request khác vừa thu hồi token này trước (race condition / reuse)
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now);

    // Thu hồi toàn bộ family — dùng khi phát hiện reuse (A08 bước 2) và khi đổi mật khẩu (A11/A12)
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    void revokeAllByFamilyId(@Param("familyId") UUID familyId, @Param("now") Instant now);

    // Thu hồi TOÀN BỘ token của user, mọi family — dùng ở A11 (reset-password) bước 6
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
