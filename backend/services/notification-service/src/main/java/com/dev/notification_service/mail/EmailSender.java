package com.dev.notification_service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Gửi email văn bản thuần qua SMTP. Không có MAIL_HOST thì Spring không tạo JavaMailSender:
 * {@link #isConfigured()} trả false và caller đánh dấu SKIPPED thay vì lỗi.
 * Không bao giờ log nội dung email (có thể chứa OTP, mật khẩu tạm) hay địa chỉ người nhận.
 */
@Component
@Slf4j
public class EmailSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;

    public EmailSender(ObjectProvider<JavaMailSender> mailSender,
                       @Value("${app.notification.mail-from:no-reply@vantix.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public boolean isConfigured() {
        return mailSender.getIfAvailable() != null;
    }

    /** @throws MailException khi SMTP từ chối hoặc không kết nối được — caller quyết định retry */
    public void send(String to, String subject, String text) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("SMTP chưa được cấu hình");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        sender.send(message);
    }
}
