package com.dev.user_service.exception;

import com.dev.commonlib_api_response.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    // 20xx — Đăng ký & xác thực email
    EMAIL_ALREADY_EXISTS(2001, "Email đã được sử dụng", HttpStatus.CONFLICT),
    EMAIL_ALREADY_VERIFIED(2002, "Email đã được xác thực trước đó", HttpStatus.CONFLICT),

    // 21xx — Đăng nhập & trạng thái tài khoản
    INVALID_CREDENTIALS(2101, "Email hoặc mật khẩu không đúng", HttpStatus.UNAUTHORIZED),
    ACCOUNT_NOT_VERIFIED(2102, "Tài khoản chưa xác thực email", HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED(2103, "Tài khoản đã bị khóa", HttpStatus.FORBIDDEN),

    // 22xx — Refresh token
    REFRESH_TOKEN_INVALID(2201, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_REUSED(2202, "Phiên đăng nhập bị thu hồi vì lý do bảo mật, vui lòng đăng nhập lại", HttpStatus.UNAUTHORIZED),

    // 23xx — OTP (gộp INVALID + EXPIRED do chuyển sang Redis TTL thuần)
    OTP_INVALID(2301, "Mã xác thực không đúng hoặc đã hết hạn", HttpStatus.BAD_REQUEST),
    OTP_ATTEMPTS_EXCEEDED(2303, "Bạn đã nhập sai quá số lần cho phép, vui lòng yêu cầu mã mới", HttpStatus.TOO_MANY_REQUESTS),

    // 24xx — Mật khẩu
    PASSWORD_INCORRECT(2401, "Mật khẩu hiện tại không đúng", HttpStatus.BAD_REQUEST),
    NEW_PASSWORD_SAME_AS_OLD(2402, "Mật khẩu mới không được trùng mật khẩu hiện tại", HttpStatus.BAD_REQUEST),
    PASSWORD_NOT_SET(2403, "Tài khoản chưa có mật khẩu, hãy dùng chức năng quên mật khẩu để đặt", HttpStatus.BAD_REQUEST),

    // 25xx — OAuth2 Google
    OAUTH_STATE_INVALID(2501, "Phiên đăng nhập Google không hợp lệ", HttpStatus.BAD_REQUEST),
    OAUTH_CODE_INVALID(2502, "Đăng nhập Google không thành công", HttpStatus.BAD_REQUEST),
    OAUTH_PROVIDER_UNAVAILABLE(2503, "Không thể kết nối tới Google, vui lòng thử lại", HttpStatus.BAD_GATEWAY),
    OAUTH_EMAIL_NOT_VERIFIED(2504, "Email Google chưa được xác minh", HttpStatus.FORBIDDEN),
    OAUTH_ACCOUNT_CONFLICT(2505, "Email đã liên kết với một tài khoản Google khác", HttpStatus.CONFLICT),
    OAUTH_EXCHANGE_CODE_INVALID(2506, "Mã đăng nhập không hợp lệ hoặc đã hết hạn", HttpStatus.BAD_REQUEST),

    // 26xx — Quản lý user / STAFF
    USER_NOT_FOUND(2601, "Không tìm thấy người dùng", HttpStatus.NOT_FOUND);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
