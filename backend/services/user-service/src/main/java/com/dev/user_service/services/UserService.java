package com.dev.user_service.services;

import com.dev.user_service.dto.request.CreateStaffRequest;
import com.dev.user_service.dto.response.UserResponse;

import java.util.UUID;

public interface UserService {
    UserResponse getCurrentUser(UUID userId);
    UserResponse createStaff(CreateStaffRequest request);
}
