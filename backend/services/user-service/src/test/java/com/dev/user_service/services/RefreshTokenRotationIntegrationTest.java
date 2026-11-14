package com.dev.user_service.services;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.user_service.AbstractUserServiceIT;
import com.dev.user_service.dto.request.LoginRequest;
import com.dev.user_service.dto.response.LoginResponse;
import com.dev.user_service.entities.User;
import com.dev.user_service.enums.UserRole;
import com.dev.user_service.enums.UserStatus;
import com.dev.user_service.exception.UserErrorCode;
import com.dev.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refresh token (A08) trên PostgreSQL thật: rotation, phát hiện reuse thu hồi cả family,
 * và refresh song song cùng một token chỉ một request thắng.
 */
class RefreshTokenRotationIntegrationTest extends AbstractUserServiceIT {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String email;
    private String password;

    @BeforeEach
    void createActiveUser() {
        email = "it-" + UUID.randomUUID() + "@example.com";
        password = UUID.randomUUID().toString();
        userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .fullName("IT Buyer")
                .role(UserRole.BUYER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    @Test
    void refreshRotatesTokenAndKeepsUser() {
        String first = login();

        LoginResponse rotated = authService.refresh(first);

        assertThat(rotated.getAuth().getRefreshToken()).isNotBlank().isNotEqualTo(first);
        assertThat(rotated.getAuth().getAccessToken()).isNotBlank();
        assertThat(rotated.getUser().getEmail()).isEqualTo(email);
    }

    @Test
    void reusingRotatedTokenRevokesWholeFamily() {
        String first = login();
        String second = authService.refresh(first).getAuth().getRefreshToken();

        assertError(() -> authService.refresh(first), UserErrorCode.REFRESH_TOKEN_REUSED);
        // Token mới nhất cùng family cũng bị thu hồi — kẻ đánh cắp và chủ token đều phải đăng nhập lại
        assertError(() -> authService.refresh(second), UserErrorCode.REFRESH_TOKEN_REUSED);
    }

    @Test
    void reuseInOneFamilyDoesNotAffectOtherSessions() {
        String deviceA = login();
        String deviceB = login();
        authService.refresh(deviceA);

        assertError(() -> authService.refresh(deviceA), UserErrorCode.REFRESH_TOKEN_REUSED);
        assertThat(authService.refresh(deviceB).getAuth().getRefreshToken()).isNotBlank();
    }

    @Test
    void concurrentRefreshWithSameTokenHasExactlyOneWinner() throws InterruptedException {
        String token = login();

        List<Outcome<LoginResponse>> outcomes = runConcurrently(10, () -> authService.refresh(token));

        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allSatisfy(o -> assertThat(o.error()).isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.REFRESH_TOKEN_REUSED)));
    }

    @Test
    void garbageTokenIsInvalid() {
        assertError(() -> authService.refresh("not-a-jwt"), UserErrorCode.REFRESH_TOKEN_INVALID);
        assertError(() -> authService.refresh(" "), UserErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() {
        String accessToken = loginResponse().getAuth().getAccessToken();
        assertError(() -> authService.refresh(accessToken), UserErrorCode.REFRESH_TOKEN_INVALID);
    }

    private String login() {
        return loginResponse().getAuth().getRefreshToken();
    }

    private LoginResponse loginResponse() {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return authService.login(request);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, UserErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
