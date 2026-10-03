package com.dev.commonlib_api_response.exception;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;

@Slf4j
public abstract class BaseGlobalExceptionHandler {

    // 1. Exception nghiệp vụ tự ném ra trong service — nguồn lỗi chính, ưu tiên xử lý trước
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Object>> handleAppException(AppException ex) {
        return buildResponse(ex.getErrorCode(), ex.getData());
    }

    // 2. Validation @RequestBody (@Valid trên DTO)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldErrorResponse>>> handleValidation(
            MethodArgumentNotValidException ex) {
        List<FieldErrorResponse> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldErrorResponse(f.getField(), f.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getHttpStatus())
                .body(ApiResponse.<List<FieldErrorResponse>>builder()
                        .code(CommonErrorCode.VALIDATION_FAILED.getCode())
                        .message(CommonErrorCode.VALIDATION_FAILED.getMessage())
                        .result(fieldErrors)
                        .build());
    }

    // 3. Validation @RequestParam/@PathVariable (cần @Validated ở class controller)
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        return buildResponse(CommonErrorCode.VALIDATION_FAILED, null);
    }

    // 4. Body gửi lên không parse được thành JSON hợp lệ, hoặc sai kiểu dữ liệu field
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(HttpMessageNotReadableException ex) {
        return buildResponse(CommonErrorCode.MALFORMED_REQUEST, null);
    }

    // 5. Vi phạm ràng buộc DB (unique, FK...) mà service không tự bắt trước — lưới an toàn
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation chưa được service xử lý cụ thể: {}", ex.getMessage());
        return buildResponse(CommonErrorCode.DATA_CONFLICT, null);
    }

    // 6. @PreAuthorize/@PostAuthorize chặn quyền (Spring Security 6.3+ ném exception này thay vì AccessDeniedException cũ)
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        return buildResponse(CommonErrorCode.ACCESS_DENIED, null);
    }

    // 7. Lưới cuối cùng — không lộ exception thật/stack trace ra client
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex) {
        log.error("Lỗi không xác định", ex);
        return buildResponse(CommonErrorCode.INTERNAL_ERROR, null);
    }

    protected <T> ResponseEntity<ApiResponse<T>> buildResponse(ErrorCode errorCode, T result) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.<T>builder()
                        .code(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .result(result)
                        .build());
    }
}