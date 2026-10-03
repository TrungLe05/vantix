package com.dev.api_gateway.exception;

import com.dev.commonlib_api_response.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GatewayErrorCode implements ErrorCode {

    TOKEN_MISSING(9001, "Thiếu access token", HttpStatus.UNAUTHORIZED),
    TOKEN_INVALID(9002, "Access token không hợp lệ", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED(9003, "Access token đã hết hạn", HttpStatus.UNAUTHORIZED),
    PASSWORD_CHANGE_REQUIRED(9004, "Bạn cần đổi mật khẩu trước khi tiếp tục", HttpStatus.FORBIDDEN);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
