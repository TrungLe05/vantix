package com.dev.notification_service.enums;

import com.dev.commonlib_api_response.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// Dải 6xxx: 60xx thông báo
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
    // Không tồn tại và của người khác trả cùng một lỗi (chống dò, D17)
    NOTIFICATION_NOT_FOUND(6001, "Không tìm thấy thông báo", HttpStatus.NOT_FOUND);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
