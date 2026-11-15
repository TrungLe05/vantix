package com.dev.notification_service.client;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

@FeignClient(name = "user-service", url = "${app.user-service.base-url}")
public interface UserContactFeignClient {

    @GetMapping("/internal/users/{userId}/contact")
    ApiResponse<UserContact> getContact(
            @RequestHeader("X-User-Id") String callerId,
            @RequestHeader("X-User-Role") String callerRole,
            @PathVariable UUID userId
    );

    record UserContact(UUID userId, String email, String fullName) {
    }
}
