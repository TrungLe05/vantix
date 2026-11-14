package com.dev.user_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.user_service.dto.response.UserContactResponse;
import com.dev.user_service.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API chỉ cho service khác gọi (D30, D34): /api/internal/** không có route ở Gateway. Gateway luôn
 * xóa X-User-Role của client và không bao giờ phát role INTERNAL, nên role này chỉ đến được từ
 * service trong mạng nội bộ (cách ly mạng thật sự ở P9).
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;

    // notification-service lấy email để gửi thông báo đơn hàng: Order/Payment không giữ email
    @GetMapping("/{userId}/contact")
    @PreAuthorize("hasRole('INTERNAL')")
    public ApiResponse<UserContactResponse> getContact(@PathVariable UUID userId) {
        return ApiResponse.<UserContactResponse>builder()
                .result(userService.getContact(userId))
                .build();
    }
}
