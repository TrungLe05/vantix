package com.dev.notification_service.enums;

public enum EmailStatus {
    PENDING,
    SENT,
    // Lỗi tạm thời, còn được thử lại theo next_email_attempt_at (NULL = đã hết lượt)
    FAILED,
    // Không gửi được và không thử lại: SMTP chưa cấu hình hoặc user không còn tồn tại
    SKIPPED
}
