package com.dev.user_service.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class UserContactResponse {
    private UUID userId;
    private String email;
    private String fullName;
}
