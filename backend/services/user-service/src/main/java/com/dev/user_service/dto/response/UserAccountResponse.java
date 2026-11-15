package com.dev.user_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/** Thông tin tài khoản không định danh cho bot-detection (feature tuổi tài khoản, D39). */
@Getter
@Builder
public class UserAccountResponse {
    private UUID userId;
    private Instant createdAt;
}
