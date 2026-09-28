package com.dev.user_service.dto.response;

import com.dev.user_service.enums.OauthProvider;
import com.dev.user_service.enums.UserRole;
import com.dev.user_service.enums.UserStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class UserResponse {
    private UUID id;
    private String email;
    private String fullName;
    private UserRole role;
    private UserStatus status;
    private OauthProvider oauthProvider;
    private boolean mustChangePassword;
}
