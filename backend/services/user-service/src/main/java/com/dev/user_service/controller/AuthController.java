package com.dev.user_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.commonlibjwt.jwt.JwtProperties;
import com.dev.user_service.dto.request.*;
import com.dev.user_service.dto.response.LoginResponse;
import com.dev.user_service.dto.response.UserResponse;
import com.dev.user_service.services.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final JwtProperties jwtProperties;
    @Value("${app.cookie.secure}")
    private boolean secure;

    @Value("${app.cookie.same-site}")
    private String sameSite;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse result = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<UserResponse>builder()
                        .message("Đăng ký thành công. Vui lòng kiểm tra email để lấy mã xác thực")
                        .result(result)
                        .build());
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request);
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .message("Xác thực email thành công. Bạn có thể đăng nhập")
                        .build()
        );
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .message("Nếu email hợp lệ, mã xác thực mới đã được gửi")
                        .build()
        );
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse result = authService.login(request);
        ResponseCookie cookie = create(result.getAuth().getRefreshToken());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.<LoginResponse>builder().result(result).build());
    }

    @PostMapping("/oauth2/exchange")
    public ResponseEntity<ApiResponse<LoginResponse>> exchangeOAuthCode(@Valid @RequestBody OAuth2ExchangeRequest request) {
        LoginResponse result = authService.exchangeOAuthCode(request);
        ResponseCookie cookie = create(result.getAuth().getRefreshToken());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.<LoginResponse>builder().result(result).build());
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken
    ) {
        LoginResponse result = authService.refresh(refreshToken);
        ResponseCookie cookie = create(result.getAuth().getRefreshToken());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.<LoginResponse>builder().result(result).build());
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            @RequestHeader("X-User-Id") String userId
    ) {
        authService.logout(refreshToken, UUID.fromString(userId));

        ResponseCookie cleared = clear();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .body(ApiResponse.<Void>builder().message("Đăng xuất thành công").build());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .message("Nếu email hợp lệ, mã đặt lại mật khẩu đã được gửi")
                        .build()
        );
    }

    // helper method
    private ResponseCookie create(String refreshToken) {
        return ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(secure)
                .path("/api/auth") // chỉ gửi cookie cho route /api/auth/** — không rò sang các API khác
                .sameSite(sameSite)
                .maxAge(jwtProperties.getExpirationRefresh())
                .build();
    }


    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .message("Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại")
                        .build()
        );
    }



    @PatchMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @RequestHeader("X-User-Id") String userId
    ) {
        authService.changePassword(UUID.fromString(userId), request);
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .message("Đổi mật khẩu thành công. Vui lòng đăng nhập lại")
                        .build()
        );
    }

    /** Cookie rỗng, maxAge=0 — dùng để xóa cookie khi logout (A09) */
    private ResponseCookie clear() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .path("/api/auth")
                .sameSite(sameSite)
                .maxAge(0)
                .build();
    }
}
