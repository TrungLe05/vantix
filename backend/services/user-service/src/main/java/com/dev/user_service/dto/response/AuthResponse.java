package com.dev.user_service.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {
    private String accessToken;

    @JsonIgnore
    private String refreshToken;
    private String tokenType;
    private long expiresIn;
}
