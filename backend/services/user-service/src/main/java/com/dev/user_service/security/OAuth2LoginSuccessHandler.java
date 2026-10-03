package com.dev.user_service.security;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.user_service.services.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;
    private final OAuth2RedirectUrlBuilder redirectUrlBuilder;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        // Scope có "openid" nên principal luôn là OidcUser — sub/email/emailVerified/fullName
        // lấy trực tiếp qua IdTokenClaimAccessor, không cần tự gọi userinfo endpoint
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        // Session chỉ phục vụ luồng OAuth2 (state/nonce). Hệ thống đã tự phát hành JWT nên hủy ngay,
        // nếu không SecurityContext đã xác thực sẽ sống theo timeout của session
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        try {
            String oneTimeCode = authService.handleGoogleLoginSuccess(
                    oidcUser.getSubject(),
                    oidcUser.getEmail(),
                    Boolean.TRUE.equals(oidcUser.getEmailVerified()),
                    oidcUser.getFullName()
            );
            response.sendRedirect(redirectUrlBuilder.buildSuccessUrl(oneTimeCode));
        } catch (AppException e) {
            log.warn("Xử lý Google login thất bại sau khi xác thực: {}", e.getErrorCode().getMessage());
            response.sendRedirect(redirectUrlBuilder.buildErrorUrl(e.getErrorCode().getCode()));
        }
    }
}