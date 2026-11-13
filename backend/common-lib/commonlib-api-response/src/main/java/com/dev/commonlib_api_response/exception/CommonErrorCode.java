package com.dev.commonlib_api_response.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode{

    INTERNAL_ERROR(1000, "Lỗi hệ thống, vui lòng thử lại sau", HttpStatus.INTERNAL_SERVER_ERROR),
    VALIDATION_FAILED(1001, "Dữ liệu không hợp lệ", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(1002, "Nội dung request không đúng định dạng", HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(1003, "Chưa xác thực", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1004, "Bạn không có quyền thực hiện thao tác này", HttpStatus.FORBIDDEN),
    DATA_CONFLICT(1005, "Dữ liệu bị xung đột", HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
