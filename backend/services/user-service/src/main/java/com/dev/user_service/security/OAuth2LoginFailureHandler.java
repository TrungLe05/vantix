package com.dev.user_service.security;

import com.dev.user_service.exception.UserErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private final OAuth2RedirectUrlBuilder redirectUrlBuilder;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        // Session chỉ phục vụ luồng OAuth2 (state/nonce). Hệ thống đã tự phát hành JWT nên hủy ngay,
        // nếu không SecurityContext đã xác thực sẽ sống theo timeout của session
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        // Bao trùm mọi lý do thất bại phía Spring Security: user bấm Hủy ở Google (access_denied),
        // lỗi state/CSRF, Google trả lỗi khi đổi code... đều rơi vào đây, không cần phân loại chi tiết
        // Chỉ log mã lỗi OAuth2 (vd access_denied), không log message/description do provider trả về
        String reason = exception instanceof OAuth2AuthenticationException oauth2Exception
                ? oauth2Exception.getError().getErrorCode()
                : exception.getClass().getSimpleName();
        log.warn("OAuth2 login thất bại: {}", reason);
        response.sendRedirect(redirectUrlBuilder.buildErrorUrl(UserErrorCode.OAUTH_CODE_INVALID.getCode()));
    }
}