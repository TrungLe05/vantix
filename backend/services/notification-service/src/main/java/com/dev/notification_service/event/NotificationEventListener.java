package com.dev.notification_service.event;

import com.dev.commonlib_kafka.event.EmailVerificationRequestedEvent;
import com.dev.commonlib_kafka.event.ForgotPasswordRequestedEvent;
import com.dev.commonlib_kafka.event.OrderRefundedEvent;
import com.dev.commonlib_kafka.event.RefundRequestedEvent;
import com.dev.commonlib_kafka.event.StaffAccountCreatedEvent;
import com.dev.commonlib_kafka.event.TicketsIssuedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.notification_service.enums.NotificationType;
import com.dev.notification_service.service.AccountEmailService;
import com.dev.notification_service.service.NotificationContent;
import com.dev.notification_service.service.NotificationRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Consumer của notification-service. Mọi handler idempotent: thông báo đơn hàng chống lặp bằng
 * dedup_key; email tài khoản có thể gửi lặp khi Kafka phát lại (chấp nhận — OTP mới nhất vẫn đúng).
 * Không bao giờ log payload: user.* chứa OTP / mật khẩu tạm.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final JsonMapper jsonMapper;
    private final NotificationRecorder recorder;
    private final AccountEmailService accountEmailService;

    // ---------- Order (P5) ----------

    @KafkaListener(topics = KafkaTopics.ORDER_TICKETS_ISSUED,
            groupId = "${app.notification.kafka-consumer-group:notification-service}")
    public void onTicketsIssued(String payload) {
        consume(KafkaTopics.ORDER_TICKETS_ISSUED, () -> {
            TicketsIssuedEvent event = jsonMapper.readValue(payload, TicketsIssuedEvent.class);
            recorder.record(event.buyerId(), NotificationType.TICKETS_ISSUED,
                    NotificationContent.ticketsIssued(event.orderId(), event.ticketCount(),
                            event.totalAmount(), event.currency()),
                    event.orderId(), "tickets-issued:" + event.orderId());
        });
    }

    @KafkaListener(topics = KafkaTopics.ORDER_REFUND_REQUESTED,
            groupId = "${app.notification.kafka-consumer-group:notification-service}")
    public void onRefundRequested(String payload) {
        consume(KafkaTopics.ORDER_REFUND_REQUESTED, () -> {
            RefundRequestedEvent event = jsonMapper.readValue(payload, RefundRequestedEvent.class);
            recorder.record(event.buyerId(), NotificationType.REFUND_REQUESTED,
                    NotificationContent.refundRequested(event.orderId(), event.reason(),
                            event.amount(), event.currency()),
                    event.orderId(), "refund-requested:" + event.refundId());
        });
    }

    @KafkaListener(topics = KafkaTopics.ORDER_REFUNDED,
            groupId = "${app.notification.kafka-consumer-group:notification-service}")
    public void onOrderRefunded(String payload) {
        consume(KafkaTopics.ORDER_REFUNDED, () -> {
            OrderRefundedEvent event = jsonMapper.readValue(payload, OrderRefundedEvent.class);
            recorder.record(event.buyerId(), NotificationType.REFUND_COMPLETED,
                    NotificationContent.refundCompleted(event.orderId(), event.amount(), event.currency()),
                    event.orderId(), "refunded:" + event.refundId());
        });
    }

    // ---------- user-service (P1) ----------
    // Group riêng với auto.offset.reset=latest: lần đầu notification-service chạy không được gửi lại toàn
    // bộ OTP / mật khẩu tạm tồn đọng từ trước tới nay (topic này đã có producer từ P1).

    @KafkaListener(topics = KafkaTopics.USER_EMAIL_VERIFICATION_REQUESTED,
            groupId = "${app.notification.account-consumer-group:notification-service-account}",
            properties = "auto.offset.reset=latest")
    public void onEmailVerificationRequested(String payload,
                                             @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {
        consume(KafkaTopics.USER_EMAIL_VERIFICATION_REQUESTED, () -> accountEmailService.sendEmailVerification(
                jsonMapper.readValue(payload, EmailVerificationRequestedEvent.class), Instant.ofEpochMilli(timestamp)));
    }

    @KafkaListener(topics = KafkaTopics.USER_FORGOT_PASSWORD_REQUESTED,
            groupId = "${app.notification.account-consumer-group:notification-service-account}",
            properties = "auto.offset.reset=latest")
    public void onForgotPasswordRequested(String payload,
                                          @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp) {
        consume(KafkaTopics.USER_FORGOT_PASSWORD_REQUESTED, () -> accountEmailService.sendPasswordReset(
                jsonMapper.readValue(payload, ForgotPasswordRequestedEvent.class), Instant.ofEpochMilli(timestamp)));
    }

    @KafkaListener(topics = KafkaTopics.USER_STAFF_ACCOUNT_CREATED,
            groupId = "${app.notification.account-consumer-group:notification-service-account}",
            properties = "auto.offset.reset=latest")
    public void onStaffAccountCreated(String payload) {
        consume(KafkaTopics.USER_STAFF_ACCOUNT_CREATED, () -> accountEmailService.sendStaffAccount(
                jsonMapper.readValue(payload, StaffAccountCreatedEvent.class)));
    }

    private void consume(String topic, Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException ex) {
            // Chỉ loại lỗi: payload có thể chứa OTP, mật khẩu tạm, email
            log.error("Không xử lý được {} ({}); Kafka sẽ retry", topic, ex.getClass().getSimpleName());
            throw new IllegalStateException("Cannot process " + topic);
        }
    }
}
