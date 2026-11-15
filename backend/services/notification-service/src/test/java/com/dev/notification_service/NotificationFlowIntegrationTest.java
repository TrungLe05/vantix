package com.dev.notification_service;

import com.dev.commonlib_kafka.event.EmailVerificationRequestedEvent;
import com.dev.commonlib_kafka.event.OrderRefundedEvent;
import com.dev.commonlib_kafka.event.RefundRequestedEvent;
import com.dev.commonlib_kafka.event.StaffAccountCreatedEvent;
import com.dev.commonlib_kafka.event.TicketsIssuedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.notification_service.client.UserContactClient;
import com.dev.notification_service.client.UserContactFeignClient;
import com.dev.notification_service.event.NotificationEventListener;
import com.dev.notification_service.mail.EmailSender;
import com.dev.notification_service.service.EmailDispatchScheduler;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notification-service trên PostgreSQL thật; SMTP (EmailSender), user-service và Kafka giả lập:
 * consumer idempotent, API danh sách/đã đọc chỉ của chính user, gửi email có retry, email tài khoản
 * (OTP) không lưu DB và bỏ OTP quá hạn.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationFlowIntegrationTest {

    @MockitoBean
    private EmailSender emailSender;

    @MockitoBean
    private UserContactClient userContactClient;

    @Autowired
    private NotificationEventListener listener;

    @Autowired
    private EmailDispatchScheduler emailScheduler;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;
    private UUID buyer;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        buyer = UUID.randomUUID();
        orderId = UUID.randomUUID();
        // DB test dùng chung giữa các lần chạy: email tồn của lần trước không được lọt vào lô của test này
        jdbc.update("UPDATE notifications SET next_email_attempt_at = NULL WHERE next_email_attempt_at IS NOT NULL");
        when(emailSender.isConfigured()).thenReturn(true);
        when(userContactClient.findContact(buyer))
                .thenReturn(Optional.of(new UserContactFeignClient.UserContact(buyer, "buyer@example.com", "Alex Buyer")));
    }

    // ---------- consumer ----------

    @Test
    void ticketsIssuedCreatesOneNotificationEvenWhenDeliveredTwice() {
        String payload = json(new TicketsIssuedEvent(orderId, buyer, UUID.randomUUID(), 3,
                new BigDecimal("150.00"), "USD", Instant.now()));

        listener.onTicketsIssued(payload);
        listener.onTicketsIssued(payload);

        assertThat(count(buyer)).isEqualTo(1);
        Map<String, Object> row = row(buyer);
        assertThat(row).containsEntry("type", "TICKETS_ISSUED").containsEntry("email_status", "PENDING")
                .containsEntry("link", "/orders/" + orderId).containsEntry("title", "Your tickets are confirmed");
        assertThat((String) row.get("body")).contains("$150.00").contains("3 tickets are ready");
    }

    @Test
    void refundRequestedTextDependsOnReasonAndRefundCompletedIsSeparate() {
        UUID refundId = UUID.randomUUID();
        listener.onRefundRequested(json(new RefundRequestedEvent(refundId, orderId, buyer,
                new BigDecimal("75.00"), "USD", "EVENT_CANCELLED", Instant.now())));
        listener.onOrderRefunded(json(new OrderRefundedEvent(refundId, orderId, buyer, UUID.randomUUID(),
                new BigDecimal("75.00"), "USD", "EVENT_CANCELLED", Instant.now())));
        listener.onOrderRefunded(json(new OrderRefundedEvent(refundId, orderId, buyer, UUID.randomUUID(),
                new BigDecimal("75.00"), "USD", "EVENT_CANCELLED", Instant.now())));

        assertThat(jdbc.queryForList("SELECT type FROM notifications WHERE user_id = ? ORDER BY created_at",
                String.class, buyer)).containsExactly("REFUND_REQUESTED", "REFUND_COMPLETED");
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE user_id = ? AND type = 'REFUND_REQUESTED'",
                String.class, buyer)).contains("event you booked was cancelled").contains("$75.00");
    }

    @Test
    void allListenersAreRegistered() {
        // Thiếu spring-boot-starter-kafka thì @KafkaListener bị bỏ qua âm thầm (lỗi đã gặp ở order-service)
        assertThat(listenerRegistry.getListenerContainers()).flatExtracting(c -> java.util.List.of(
                        c.getContainerProperties().getTopics()))
                .contains(KafkaTopics.ORDER_TICKETS_ISSUED, KafkaTopics.ORDER_REFUND_REQUESTED,
                        KafkaTopics.ORDER_REFUNDED, KafkaTopics.USER_EMAIL_VERIFICATION_REQUESTED,
                        KafkaTopics.USER_FORGOT_PASSWORD_REQUESTED, KafkaTopics.USER_STAFF_ACCOUNT_CREATED);
    }

    // ---------- REST ----------

    @Test
    void userSeesOnlyOwnNotificationsAndCanMarkThemRead() throws Exception {
        issue(buyer, UUID.randomUUID());
        issue(buyer, UUID.randomUUID());
        UUID stranger = UUID.randomUUID();
        issue(stranger, UUID.randomUUID());
        UUID mine = (UUID) row(buyer).get("id");

        mvc.perform(as(buyer, get("/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.totalElements").value(2))
                .andExpect(jsonPath("$.result.content[0].read").value(false));
        mvc.perform(as(buyer, get("/notifications/unread-count")))
                .andExpect(jsonPath("$.result.count").value(2));

        // Thông báo của người khác: cùng lỗi với không tồn tại
        mvc.perform(as(stranger, patch("/notifications/" + mine + "/read")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(6001));

        mvc.perform(as(buyer, patch("/notifications/" + mine + "/read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.read").value(true));
        mvc.perform(as(buyer, get("/notifications/unread-count")))
                .andExpect(jsonPath("$.result.count").value(1));
        mvc.perform(as(buyer, post("/notifications/read-all")))
                .andExpect(jsonPath("$.result.updated").value(1));
        mvc.perform(as(buyer, get("/notifications/unread-count")))
                .andExpect(jsonPath("$.result.count").value(0));
        mvc.perform(as(stranger, get("/notifications/unread-count")))
                .andExpect(jsonPath("$.result.count").value(1));
    }

    @Test
    void apiRequiresIdentityButWebSocketHandshakeIsPublic() throws Exception {
        mvc.perform(get("/notifications")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1003));
        mvc.perform(post("/notifications/ws-ticket")).andExpect(status().isUnauthorized());
        mvc.perform(as(buyer, post("/notifications/ws-ticket")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.ticket").isNotEmpty())
                .andExpect(jsonPath("$.result.expiresAt").isNotEmpty());
    }

    // ---------- email ----------

    @Test
    void dueEmailIsSentOnceToTheUsersAddress() {
        issue(buyer, orderId);

        emailScheduler.dispatchDueEmails();
        emailScheduler.dispatchDueEmails();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender, times(1)).send(eq("buyer@example.com"), eq("Your tickets are confirmed"), text.capture());
        assertThat(text.getValue()).startsWith("Hi Alex Buyer,")
                .contains("http://localhost:3000/orders/" + orderId);
        assertThat(row(buyer)).containsEntry("email_status", "SENT").containsEntry("email_attempts", 1)
                .containsEntry("next_email_attempt_at", null);
    }

    @Test
    void smtpFailureIsRetriedWithBackoffAndStopsAfterMaxAttempts() {
        doThrow(new MailSendException("smtp down for buyer@example.com"))
                .when(emailSender).send(anyString(), anyString(), anyString());
        issue(buyer, orderId);
        UUID id = (UUID) row(buyer).get("id");

        emailScheduler.dispatchDueEmails();
        assertThat(row(buyer)).containsEntry("email_status", "FAILED").containsEntry("email_attempts", 1)
                .containsEntry("email_last_error", "MailSendException");
        assertThat(secondsUntilRetry(id)).isBetween(20.0, 35.0);      // 30s × 2^0

        for (int attempt = 2; attempt <= 5; attempt++) {
            makeDue(id);
            emailScheduler.dispatchDueEmails();
        }
        assertThat(row(buyer)).containsEntry("email_attempts", 5).containsEntry("next_email_attempt_at", null);
        makeDue(id);
        jdbc.update("UPDATE notifications SET next_email_attempt_at = NULL WHERE id = ?", id);
        emailScheduler.dispatchDueEmails();
        verify(emailSender, times(5)).send(anyString(), anyString(), anyString());
    }

    @Test
    void emailIsSkippedWhenSmtpIsNotConfiguredOrUserNoLongerExists() {
        when(emailSender.isConfigured()).thenReturn(false);
        issue(buyer, orderId);
        emailScheduler.dispatchDueEmails();
        assertThat(row(buyer)).containsEntry("email_status", "SKIPPED").containsEntry("email_last_error", "mail_not_configured");

        when(emailSender.isConfigured()).thenReturn(true);
        UUID ghost = UUID.randomUUID();
        when(userContactClient.findContact(ghost)).thenReturn(Optional.empty());
        issue(ghost, UUID.randomUUID());
        emailScheduler.dispatchDueEmails();
        assertThat(row(ghost)).containsEntry("email_status", "SKIPPED").containsEntry("email_last_error", "user_not_found");
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void userServiceOutageIsRetriedNotSkipped() {
        when(userContactClient.findContact(buyer)).thenThrow(new UserContactClient.UserServiceUnavailableException());
        issue(buyer, orderId);

        emailScheduler.dispatchDueEmails();

        assertThat(row(buyer)).containsEntry("email_status", "FAILED")
                .containsEntry("email_last_error", "UserServiceUnavailableException");
        assertThat(row(buyer).get("next_email_attempt_at")).isNotNull();
    }

    // ---------- email tài khoản (user.*) ----------

    @Test
    void verificationOtpIsEmailedButNeverStored() {
        UUID user = UUID.randomUUID();
        listener.onEmailVerificationRequested(json(new EmailVerificationRequestedEvent(
                user, "new@example.com", "New User", "482913")), Instant.now().toEpochMilli());

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("new@example.com"), eq("Verify your Vantix account"), text.capture());
        assertThat(text.getValue()).contains("482913");
        assertThat(count(user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE body LIKE '%482913%'", Integer.class))
                .isZero();
    }

    @Test
    void expiredOtpIsNotSentButStaffPasswordStillIs() {
        listener.onEmailVerificationRequested(json(new EmailVerificationRequestedEvent(
                        UUID.randomUUID(), "late@example.com", "Late", "111111")),
                Instant.now().minus(11, ChronoUnit.MINUTES).toEpochMilli());
        verify(emailSender, never()).send(eq("late@example.com"), anyString(), anyString());

        listener.onStaffAccountCreated(json(new StaffAccountCreatedEvent(
                UUID.randomUUID(), "staff@example.com", "Gate Staff", "Tmp-pass-9")));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("staff@example.com"), eq("Your Vantix staff account"), text.capture());
        assertThat(text.getValue()).contains("Tmp-pass-9").contains("http://localhost:3000/login");
    }

    @Test
    void accountEmailFailureIsRethrownSoKafkaRetries() {
        doThrow(new MailSendException("down")).when(emailSender).send(anyString(), anyString(), anyString());
        String payload = json(new StaffAccountCreatedEvent(UUID.randomUUID(), "staff2@example.com", "S", "pw"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onStaffAccountCreated(payload))
                .isInstanceOf(IllegalStateException.class)
                // Message lỗi không được mang mật khẩu tạm hay địa chỉ email
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("pw").doesNotContain("staff2@"));
    }

    // ---------- helpers ----------

    private void issue(UUID userId, UUID order) {
        listener.onTicketsIssued(json(new TicketsIssuedEvent(order, userId, UUID.randomUUID(), 1,
                new BigDecimal("20.00"), "USD", Instant.now())));
    }

    private String json(Object event) {
        return jsonMapper.writeValueAsString(event);
    }

    private static MockHttpServletRequestBuilder as(UUID userId, MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", userId.toString()).header("X-User-Role", "BUYER");
    }

    private int count(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class, userId);
    }

    private Map<String, Object> row(UUID userId) {
        return jdbc.queryForMap("SELECT * FROM notifications WHERE user_id = ? ORDER BY created_at LIMIT 1", userId);
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE notifications SET next_email_attempt_at = now() - interval '1 second' " +
                "WHERE id = ? AND next_email_attempt_at IS NOT NULL", id);
    }

    private double secondsUntilRetry(UUID id) {
        return jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (next_email_attempt_at - now())) FROM notifications WHERE id = ?",
                Double.class, id);
    }
}
