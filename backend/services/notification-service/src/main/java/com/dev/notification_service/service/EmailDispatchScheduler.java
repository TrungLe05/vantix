package com.dev.notification_service.service;

import com.dev.notification_service.client.UserContactClient;
import com.dev.notification_service.client.UserContactFeignClient;
import com.dev.notification_service.entity.Notification;
import com.dev.notification_service.enums.EmailStatus;
import com.dev.notification_service.mail.EmailSender;
import com.dev.notification_service.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Gửi email cho thông báo trạng thái PENDING/FAILED đến hạn. Cách làm giống outbox (D25): giành dòng,
 * gửi ngoài transaction, ghi kết quả; lỗi tạm thời thì backoff base × 2^attempt, hết max-attempts thì
 * dừng ở FAILED + log NOTIFICATION_EMAIL_GAVE_UP. Thông báo trong ứng dụng không phụ thuộc email.
 */
@Component
@Slf4j
public class EmailDispatchScheduler {

    private final NotificationRepository notificationRepository;
    private final UserContactClient userContactClient;
    private final EmailSender emailSender;
    private final TransactionTemplate transactionTemplate;
    private final String frontendBaseUrl;
    private final Duration baseBackoff;
    private final int maxAttempts;

    public EmailDispatchScheduler(
            NotificationRepository notificationRepository,
            UserContactClient userContactClient,
            EmailSender emailSender,
            TransactionTemplate transactionTemplate,
            @Value("${app.notification.frontend-base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${app.notification.email-base-backoff:PT30S}") Duration baseBackoff,
            @Value("${app.notification.email-max-attempts:5}") int maxAttempts
    ) {
        this.notificationRepository = notificationRepository;
        this.userContactClient = userContactClient;
        this.emailSender = emailSender;
        this.transactionTemplate = transactionTemplate;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
        this.baseBackoff = baseBackoff;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(initialDelayString = "${app.notification.email-initial-delay-ms:3000}",
            fixedDelayString = "${app.notification.email-poll-interval-ms:2000}")
    public void dispatchDueEmails() {
        for (UUID id : notificationRepository.findDueEmailIds(20)) {
            Integer claimed = transactionTemplate.execute(status -> notificationRepository.claimEmail(id));
            if (claimed != null && claimed == 1) {
                dispatch(id);
            }
        }
    }

    private void dispatch(UUID id) {
        Notification notification = notificationRepository.findById(id).orElse(null);
        if (notification == null) {
            return;
        }
        if (!emailSender.isConfigured()) {
            finish(id, EmailStatus.SKIPPED, "mail_not_configured");
            return;
        }
        try {
            Optional<UserContactFeignClient.UserContact> contact = userContactClient.findContact(notification.getUserId());
            if (contact.isEmpty() || contact.get().email() == null) {
                finish(id, EmailStatus.SKIPPED, "user_not_found");
                return;
            }
            emailSender.send(contact.get().email(), notification.getTitle(), text(notification, contact.get()));
            finish(id, EmailStatus.SENT, null);
        } catch (RuntimeException ex) {
            fail(notification, ex);
        }
    }

    private String text(Notification notification, UserContactFeignClient.UserContact contact) {
        String greeting = contact.fullName() == null || contact.fullName().isBlank()
                ? "Hi," : "Hi " + contact.fullName() + ",";
        String link = notification.getLink() == null ? "" : "\n\nView your order: " + frontendBaseUrl + notification.getLink();
        return greeting + "\n\n" + notification.getBody() + link + "\n\n- Vantix";
    }

    private void finish(UUID id, EmailStatus status, String reason) {
        transactionTemplate.executeWithoutResult(tx -> notificationRepository.findById(id).ifPresent(n -> {
            n.setEmailStatus(status);
            n.setEmailAttempts(n.getEmailAttempts() + (status == EmailStatus.SKIPPED ? 0 : 1));
            n.setNextEmailAttemptAt(null);
            n.setEmailLastError(reason);
        }));
        if (status == EmailStatus.SKIPPED) {
            log.info("Bỏ qua email notificationId={} reason={}", id, reason);
        }
    }

    private void fail(Notification notification, RuntimeException ex) {
        // Chỉ ghi loại lỗi: message của SMTP/Feign có thể chứa địa chỉ email
        String errorType = ex.getClass().getSimpleName();
        int attempts = notification.getEmailAttempts() + 1;
        boolean gaveUp = attempts >= maxAttempts;
        transactionTemplate.executeWithoutResult(tx -> notificationRepository.findById(notification.getId()).ifPresent(n -> {
            n.setEmailStatus(EmailStatus.FAILED);
            n.setEmailAttempts(attempts);
            n.setEmailLastError(errorType);
            n.setNextEmailAttemptAt(gaveUp ? null : Instant.now().plus(baseBackoff.multipliedBy(1L << (attempts - 1))));
        }));
        if (gaveUp) {
            log.error("NOTIFICATION_EMAIL_GAVE_UP notificationId={} attempts={} lastError={}",
                    notification.getId(), attempts, errorType);
        } else {
            log.warn("Gửi email thất bại notificationId={} attempt={} error={}; sẽ thử lại",
                    notification.getId(), attempts, errorType);
        }
    }
}
