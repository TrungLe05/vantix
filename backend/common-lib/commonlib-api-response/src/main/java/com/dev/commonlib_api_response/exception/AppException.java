package com.dev.commonlib_api_response.exception;

import lombok.Getter;

@Getter
public class AppException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object data; // dữ liệu bổ sung đi kèm lỗi, đưa vào result — null nếu không có

    public AppException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public AppException(ErrorCode errorCode, Object data) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.data = data;
    }
}
