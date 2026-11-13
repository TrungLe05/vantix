package com.dev.user_service.services;

import com.dev.user_service.dto.request.*;
import com.dev.user_service.dto.response.LoginResponse;
import com.dev.user_service.dto.response.UserResponse;

import java.util.UUID;

public interface AuthService {
    UserResponse register(RegisterRequest request);
    void verifyEmail(VerifyEmailRequest request);
    void resendVerification(ResendVerificationRequest request);
    LoginResponse login(LoginRequest request);
    String handleGoogleLoginSuccess(String sub, String email, boolean emailVerified, String fullName);
    LoginResponse exchangeOAuthCode(OAuth2ExchangeRequest request);
    LoginResponse refresh(String rawRefreshToken);
    void logout(String refreshToken, UUID userId);
    void forgotPassword(ForgotPasswordRequest request);
    void resetPassword(ResetPasswordRequest request);
    void changePassword(UUID userId, ChangePasswordRequest request);
}
