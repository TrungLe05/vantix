package com.dev.notification_service.service;

import com.dev.commonlib_kafka.event.EmailVerificationRequestedEvent;
import com.dev.commonlib_kafka.event.ForgotPasswordRequestedEvent;
import com.dev.commonlib_kafka.event.StaffAccountCreatedEvent;
import com.dev.notification_service.mail.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Email tài khoản từ user-service (P1): OTP xác thực, OTP quên mật khẩu, mật khẩu tạm của STAFF.
 * Các email này chứa bí mật nên KHÔNG lưu vào bảng notifications và KHÔNG log nội dung/địa chỉ —
 * chỉ log loại email và userId. Lỗi SMTP ném ra để Kafka retry bản tin.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEmailService {

    // Bằng TTL OTP ở user-service: OTP cũ hơn mốc này đã hết hạn, gửi chỉ gây nhầm lẫn
    private static final Duration OTP_VALIDITY = Duration.ofMinutes(10);

    private final EmailSender emailSender;

    @Value("${app.notification.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    public void sendEmailVerification(EmailVerificationRequestedEvent event, Instant publishedAt) {
        if (isStale(publishedAt, "EMAIL_VERIFICATION")) {
            return;
        }
        send("EMAIL_VERIFICATION", event.userId(), event.email(), "Verify your Vantix account",
                greeting(event.fullName()) + "Your verification code is " + event.otpCode()
                        + ". It expires in 10 minutes.\n\nIf you did not create a Vantix account, ignore this email.");
    }

    public void sendPasswordReset(ForgotPasswordRequestedEvent event, Instant publishedAt) {
        if (isStale(publishedAt, "PASSWORD_RESET")) {
            return;
        }
        send("PASSWORD_RESET", event.userId(), event.email(), "Reset your Vantix password",
                greeting(event.fullName()) + "Your password reset code is " + event.otpCode()
                        + ". It expires in 10 minutes.\n\nIf you did not ask to reset your password, ignore this email.");
    }

    // Không áp hạn: mật khẩu tạm vẫn dùng được (bị ép đổi ở lần đăng nhập đầu), gửi trễ còn hơn không gửi
    public void sendStaffAccount(StaffAccountCreatedEvent event) {
        send("STAFF_ACCOUNT", event.userId(), event.email(), "Your Vantix staff account",
                greeting(event.fullName()) + "An administrator created a staff account for you.\n\n"
                        + "Sign in at " + frontendBaseUrl + "/login with this email and the temporary password: "
                        + event.temporaryPassword()
                        + "\n\nYou will be asked to choose a new password the first time you sign in.");
    }

    private boolean isStale(Instant publishedAt, String type) {
        if (publishedAt != null && publishedAt.plus(OTP_VALIDITY).isBefore(Instant.now())) {
            log.info("Bỏ qua email {} đã quá hạn OTP (bản tin phát lúc {})", type, publishedAt);
            return true;
        }
        return false;
    }

    private void send(String type, java.util.UUID userId, String to, String subject, String text) {
        if (!emailSender.isConfigured()) {
            // Dev không có SMTP: không có cách nào khác để lấy OTP ngoài cấu hình MAIL_HOST — cố ý không log mã
            log.warn("EMAIL_SKIPPED type={} userId={} reason=mail_not_configured", type, userId);
            return;
        }
        try {
            emailSender.send(to, subject, text);
            log.info("Đã gửi email type={} userId={}", type, userId);
        } catch (RuntimeException ex) {
            log.warn("Gửi email type={} userId={} thất bại ({}); Kafka sẽ retry",
                    type, userId, ex.getClass().getSimpleName());
            throw new IllegalStateException("Cannot send " + type + " email");
        }
    }

    private static String greeting(String fullName) {
        return (fullName == null || fullName.isBlank() ? "Hi," : "Hi " + fullName + ",") + "\n\n";
    }
}
