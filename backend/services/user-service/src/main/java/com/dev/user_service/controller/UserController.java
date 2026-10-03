package com.dev.user_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.user_service.dto.request.CreateStaffRequest;
import com.dev.user_service.dto.response.UserResponse;
import com.dev.user_service.services.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(@RequestHeader("X-User-Id") String userId) {
        UserResponse result = userService.getCurrentUser(UUID.fromString(userId));
        return ResponseEntity.ok(ApiResponse.<UserResponse>builder().result(result).build());
    }

    @PostMapping("/staff")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> createStaff(@Valid @RequestBody CreateStaffRequest request) {
        UserResponse result = userService.createStaff(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<UserResponse>builder()
                        .message("Tạo tài khoản STAFF thành công. Mật khẩu tạm đã được gửi qua email")
                        .result(result)
                        .build());
    }
}