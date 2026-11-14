package com.dev.payment_service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_kafka.event.RefundRequestedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.payment_service.dto.request.CreateCheckoutSessionRequest;
import com.dev.payment_service.dto.response.CheckoutSessionResponse;
import com.dev.payment_service.event.PaymentOutboxScheduler;
import com.dev.payment_service.service.PaymentExpirationScheduler;
import com.dev.payment_service.service.PaymentService;
import com.dev.payment_service.service.RefundService;
import com.dev.payment_service.stripe.StripeGateway;
import com.dev.payment_service.stripe.StripeGatewayException;
import jakarta.servlet.Filter;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payment-service trên PostgreSQL thật, Stripe và Kafka giả lập: khởi tạo thanh toán idempotent,
 * webhook (chữ ký, gửi lặp), đồng bộ deadline, hoàn tiền (idempotent, vượt số tiền, retry), outbox,
 * phân quyền API nội bộ. Mỗi test dùng orderId ngẫu nhiên nên không đụng dữ liệu của nhau.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentFlowIntegrationTest {

    // Sinh lúc chạy: không để chuỗi giống secret nằm trong mã nguồn (tránh cảnh báo quét secret)
    private static final String WEBHOOK_SECRET = "whsec_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void stripeProperties(DynamicPropertyRegistry registry) {
        registry.add("app.payment.stripe.webhook-secret", () -> WEBHOOK_SECRET);
    }

    @MockitoBean
    private StripeGateway stripeGateway;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private PaymentExpirationScheduler expirationScheduler;

    @Autowired
    private PaymentOutboxScheduler outboxScheduler;

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
    private UUID orderId;
    private UUID buyerId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        orderId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        // DB test dùng chung giữa các lần chạy: đóng dữ liệu cũ để scheduler chỉ thấy bản ghi của test này
        jdbc.update("UPDATE payment_event_outbox SET published_at = now() WHERE published_at IS NULL");
        jdbc.update("UPDATE payments SET status = 'EXPIRED' WHERE status = 'PENDING'");
        jdbc.update("UPDATE refunds SET status = 'FAILED' WHERE status = 'PENDING'");
        when(stripeGateway.createCheckoutSession(any())).thenAnswer(invocation -> {
            StripeGateway.CreateSessionCommand command = invocation.getArgument(0);
            // Giống Stripe thật: cùng idempotency key (theo paymentId) → cùng một phiên
            return new StripeGateway.CheckoutSession(
                    "cs_test_" + command.paymentId(), "https://checkout.stripe.test/" + command.paymentId());
        });
    }

    // ---------- khởi tạo thanh toán ----------

    @Test
    void createCheckoutSessionIsIdempotentPerOrder() {
        CheckoutSessionResponse first = paymentService.createCheckoutSession(request("42.50"));
        CheckoutSessionResponse second = paymentService.createCheckoutSession(request("42.50"));

        assertThat(second.getPaymentId()).isEqualTo(first.getPaymentId());
        assertThat(second.getCheckoutUrl()).isEqualTo(first.getCheckoutUrl()).startsWith("https://checkout.stripe.test/");
        assertThat(payment().get("status")).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", orderId)).isEqualTo(1);
        // Lần hai dùng lại phiên đã lưu, không gọi Stripe nữa
        verify(stripeGateway, times(1)).createCheckoutSession(any());

        ArgumentCaptor<StripeGateway.CreateSessionCommand> command =
                ArgumentCaptor.forClass(StripeGateway.CreateSessionCommand.class);
        verify(stripeGateway).createCheckoutSession(command.capture());
        assertThat(command.getValue().successUrl())
                .isEqualTo("http://localhost:3000/orders/" + orderId + "?payment=success");
        assertThat(command.getValue().amount()).isEqualByComparingTo("42.50");
    }

    @Test
    void concurrentCheckoutRequestsCreateOnePaymentAndOneSession() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CheckoutSessionResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return paymentService.createCheckoutSession(request("10.00"));
                }));
            }
            start.countDown();
            List<String> urls = new ArrayList<>();
            for (Future<CheckoutSessionResponse> future : futures) {
                urls.add(future.get().getCheckoutUrl());
            }
            assertThat(urls).hasSize(8);
            assertThat(urls.stream().distinct()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", orderId)).isEqualTo(1);
    }

    @Test
    void checkoutRejectsInvalidAmountPastDeadlineAndMismatchedRetry() {
        assertError(() -> paymentService.createCheckoutSession(request("0.49")), 5003);
        assertError(() -> paymentService.createCheckoutSession(request("10.005")), 5003);
        CreateCheckoutSessionRequest late = request("10.00");
        late.setExpiresAt(Instant.now().minusSeconds(1));
        assertError(() -> paymentService.createCheckoutSession(late), 5004);
        assertThat(count("SELECT count(*) FROM payments WHERE order_id = ?", orderId)).isZero();

        paymentService.createCheckoutSession(request("10.00"));
        // Cùng Order nhưng khác số tiền / khác buyer: không được dùng lại payment cũ
        assertError(() -> paymentService.createCheckoutSession(request("11.00")), 5002);
        CreateCheckoutSessionRequest otherBuyer = request("10.00");
        otherBuyer.setBuyerId(UUID.randomUUID());
        assertError(() -> paymentService.createCheckoutSession(otherBuyer), 5002);
    }

    @Test
    void stripeFailureLeavesPaymentPendingAndRetrySucceeds() {
        // doThrow/doReturn: when(...) sẽ gọi answer mặc định của setUp với tham số null
        doThrow(new StripeGatewayException("api_connection_error", true))
                .doReturn(new StripeGateway.CheckoutSession("cs_test_retry_" + orderId, "https://checkout.stripe.test/retry"))
                .when(stripeGateway).createCheckoutSession(any());

        assertError(() -> paymentService.createCheckoutSession(request("10.00")), 5101);
        assertThat(payment()).containsEntry("status", "PENDING").containsEntry("stripe_session_id", null);

        assertThat(paymentService.createCheckoutSession(request("10.00")).getCheckoutUrl())
                .isEqualTo("https://checkout.stripe.test/retry");
    }

    // ---------- webhook ----------

    @Test
    void paidWebhookMarksSucceededAndReplayDoesNotPublishTwice() throws Exception {
        CheckoutSessionResponse session = paymentService.createCheckoutSession(request("42.50"));
        String payload = sessionEvent("evt_" + UUID.randomUUID(), "checkout.session.completed",
                "cs_test_" + session.getPaymentId(), "paid", "pi_test_1");

        webhook(payload, sign(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        webhook(payload, sign(payload)).andExpect(status().isOk());

        assertThat(payment()).containsEntry("status", "SUCCEEDED").containsEntry("stripe_payment_intent_id", "pi_test_1");
        assertThat(payment().get("paid_at")).isNotNull();
        List<Map<String, Object>> events = outbox(KafkaTopics.PAYMENT_SUCCEEDED);
        assertThat(events).hasSize(1);
        JsonNode event = jsonMapper.readTree((String) events.get(0).get("payload"));
        assertThat(event.path("orderId").stringValue()).isEqualTo(orderId.toString());
        assertThat(event.path("buyerId").stringValue()).isEqualTo(buyerId.toString());
        assertThat(event.path("amount").decimalValue()).isEqualByComparingTo("42.50");
        assertThat(events.get(0).get("message_key")).isEqualTo(orderId.toString());
    }

    @Test
    void webhookWithInvalidOrMissingSignatureIsRejectedWithoutSideEffects() throws Exception {
        CheckoutSessionResponse session = paymentService.createCheckoutSession(request("42.50"));
        String payload = sessionEvent("evt_" + UUID.randomUUID(), "checkout.session.completed",
                "cs_test_" + session.getPaymentId(), "paid", "pi_test_1");

        webhook(payload, "t=" + Instant.now().getEpochSecond() + ",v1=" + "0".repeat(64))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(5201));
        webhook(payload, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(5201));
        // Chữ ký đúng nhưng cho payload khác (bị sửa số tiền/trạng thái sau khi ký)
        webhook(payload, sign(payload.replace("paid", "unpaid")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(5201));

        assertThat(payment().get("status")).isEqualTo("PENDING");
        assertThat(outbox(KafkaTopics.PAYMENT_SUCCEEDED)).isEmpty();
    }

    @Test
    void unpaidCompletionUnknownSessionAndUnknownTypeAreAcknowledgedButIgnored() throws Exception {
        CheckoutSessionResponse session = paymentService.createCheckoutSession(request("42.50"));
        String unpaid = sessionEvent("evt_" + UUID.randomUUID(), "checkout.session.completed",
                "cs_test_" + session.getPaymentId(), "unpaid", null);
        String foreign = sessionEvent("evt_" + UUID.randomUUID(), "checkout.session.completed",
                "cs_test_someone_else_" + UUID.randomUUID(), "paid", "pi_x");
        String other = sessionEvent("evt_" + UUID.randomUUID(), "customer.created", "cus_1", null, null);

        webhook(unpaid, sign(unpaid)).andExpect(status().isOk());
        webhook(foreign, sign(foreign)).andExpect(status().isOk());
        webhook(other, sign(other)).andExpect(status().isOk());

        assertThat(payment().get("status")).isEqualTo("PENDING");
    }

    @Test
    void expiredWebhookClosesPaymentAndTellsOrderToReleaseHolds() throws Exception {
        CheckoutSessionResponse session = paymentService.createCheckoutSession(request("42.50"));
        String payload = sessionEvent("evt_" + UUID.randomUUID(), "checkout.session.expired",
                "cs_test_" + session.getPaymentId(), "unpaid", null);

        webhook(payload, sign(payload)).andExpect(status().isOk());

        assertThat(payment().get("status")).isEqualTo("EXPIRED");
        List<Map<String, Object>> events = outbox(KafkaTopics.PAYMENT_FAILED);
        assertThat(events).hasSize(1);
        assertThat(jsonMapper.readTree((String) events.get(0).get("payload")).path("reason").stringValue())
                .isEqualTo("EXPIRED");
        // Đã đóng thì không cấp lại link thanh toán
        assertError(() -> paymentService.createCheckoutSession(request("42.50")), 5004);
    }

    // ---------- đồng bộ deadline ----------

    @Test
    void schedulerExpiresStripeSessionWhenHoldDeadlinePasses() {
        CheckoutSessionResponse session = paymentService.createCheckoutSession(request("42.50"));
        passDeadline();
        when(stripeGateway.expireSession("cs_test_" + session.getPaymentId()))
                .thenReturn(new StripeGateway.SessionState("expired", "unpaid", null));

        expirationScheduler.expireDuePayments();
        expirationScheduler.expireDuePayments();

        assertThat(payment().get("status")).isEqualTo("EXPIRED");
        assertThat(outbox(KafkaTopics.PAYMENT_FAILED)).hasSize(1);
        verify(stripeGateway, times(1)).expireSession(anyString());
    }

    @Test
    void schedulerRecordsSuccessWhenBuyerPaidRightAtTheDeadline() {
        CheckoutSessionResponse session = paymentService.createCheckoutSession(request("42.50"));
        passDeadline();
        // Stripe không cho expire phiên đã thanh toán — cũng là lưới an toàn khi webhook thất lạc
        when(stripeGateway.expireSession("cs_test_" + session.getPaymentId()))
                .thenReturn(new StripeGateway.SessionState("complete", "paid", "pi_test_late"));

        expirationScheduler.expireDuePayments();

        assertThat(payment()).containsEntry("status", "SUCCEEDED").containsEntry("stripe_payment_intent_id", "pi_test_late");
        assertThat(outbox(KafkaTopics.PAYMENT_SUCCEEDED)).hasSize(1);
        assertThat(outbox(KafkaTopics.PAYMENT_FAILED)).isEmpty();
    }

    @Test
    void schedulerKeepsPaymentPendingWhileStripeIsUnavailable() {
        paymentService.createCheckoutSession(request("42.50"));
        passDeadline();
        when(stripeGateway.expireSession(anyString())).thenThrow(new StripeGatewayException("api_error", true));

        expirationScheduler.expireDuePayments();

        assertThat(payment().get("status")).isEqualTo("PENDING");
        assertThat(outbox(KafkaTopics.PAYMENT_FAILED)).isEmpty();
    }

    // ---------- hoàn tiền ----------

    @Test
    void refundRequestIsExecutedOnceAndPublishesCompletion() throws Exception {
        paidPayment("42.50");
        UUID refundId = UUID.randomUUID();
        when(stripeGateway.createRefund(eq("pi_test_1"), any(), eq(refundId)))
                .thenReturn(new StripeGateway.RefundResult("re_test_" + refundId, "succeeded", null));

        refundService.handleRefundRequested(refundEvent(refundId, "21.25"));
        refundService.handleRefundRequested(refundEvent(refundId, "21.25"));

        assertThat(refund(refundId)).containsEntry("status", "SUCCEEDED").containsEntry("reason", "BUYER_REQUEST");
        assertThat(outbox(KafkaTopics.REFUND_COMPLETED)).hasSize(1);
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stripeGateway, times(1)).createRefund(eq("pi_test_1"), amount.capture(), eq(refundId));
        assertThat(amount.getValue()).isEqualByComparingTo("21.25");
    }

    @Test
    void refundIsRejectedForUnpaidPaymentAndForAmountAboveWhatWasPaid() throws Exception {
        // Chưa thanh toán
        paymentService.createCheckoutSession(request("42.50"));
        UUID unpaidRefund = UUID.randomUUID();
        refundService.handleRefundRequested(refundEvent(unpaidRefund, "42.50"));
        assertThat(count("SELECT count(*) FROM refunds WHERE id = ?", unpaidRefund)).isZero();
        assertThat(outbox(KafkaTopics.REFUND_FAILED)).hasSize(1);

        // Đã thanh toán 42.50, hoàn 30 rồi xin hoàn thêm 20 → vượt
        String payload = sessionEvent("evt_" + UUID.randomUUID(), "checkout.session.completed",
                (String) payment().get("stripe_session_id"), "paid", "pi_test_1");
        webhook(payload, sign(payload)).andExpect(status().isOk());
        when(stripeGateway.createRefund(anyString(), any(), any())).thenAnswer(invocation ->
                new StripeGateway.RefundResult("re_test_" + invocation.getArgument(2), "succeeded", null));
        refundService.handleRefundRequested(refundEvent(UUID.randomUUID(), "30.00"));
        refundService.handleRefundRequested(refundEvent(UUID.randomUUID(), "20.00"));

        assertThat(outbox(KafkaTopics.REFUND_COMPLETED)).hasSize(1);
        assertThat(outbox(KafkaTopics.REFUND_FAILED)).hasSize(2);
        verify(stripeGateway, times(1)).createRefund(anyString(), any(), any());
    }

    @Test
    void transientStripeErrorKeepsRefundPendingUntilRetrySucceeds() throws Exception {
        paidPayment("42.50");
        UUID refundId = UUID.randomUUID();
        when(stripeGateway.createRefund(anyString(), any(), eq(refundId)))
                .thenThrow(new StripeGatewayException("api_connection_error", true))
                .thenReturn(new StripeGateway.RefundResult("re_test_" + refundId, "succeeded", null));

        refundService.handleRefundRequested(refundEvent(refundId, "42.50"));
        assertThat(refund(refundId).get("status")).isEqualTo("PENDING");
        assertThat(outbox(KafkaTopics.REFUND_COMPLETED)).isEmpty();

        refundService.retryUnsentRefunds();

        assertThat(refund(refundId).get("status")).isEqualTo("SUCCEEDED");
        assertThat(outbox(KafkaTopics.REFUND_COMPLETED)).hasSize(1);
    }

    @Test
    void permanentStripeErrorFailsRefundWithErrorCodeOnly() throws Exception {
        paidPayment("42.50");
        UUID refundId = UUID.randomUUID();
        when(stripeGateway.createRefund(anyString(), any(), eq(refundId)))
                .thenThrow(new StripeGatewayException("charge_already_refunded", false));

        refundService.handleRefundRequested(refundEvent(refundId, "42.50"));

        assertThat(refund(refundId)).containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "charge_already_refunded");
        List<Map<String, Object>> failed = outbox(KafkaTopics.REFUND_FAILED);
        assertThat(failed).hasSize(1);
        assertThat(jsonMapper.readTree((String) failed.get(0).get("payload")).path("refundId").stringValue())
                .isEqualTo(refundId.toString());
    }

    @Test
    void pendingStripeRefundIsSettledByRefundWebhook() throws Exception {
        paidPayment("42.50");
        UUID refundId = UUID.randomUUID();
        when(stripeGateway.createRefund(anyString(), any(), eq(refundId)))
                .thenReturn(new StripeGateway.RefundResult("re_test_" + refundId, "pending", null));
        refundService.handleRefundRequested(refundEvent(refundId, "42.50"));
        assertThat(refund(refundId).get("status")).isEqualTo("PENDING");
        // Đã gửi Stripe (có stripe_refund_id) nên scheduler retry không gọi lại
        refundService.retryUnsentRefunds();
        verify(stripeGateway, times(1)).createRefund(anyString(), any(), any());

        String payload = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"refund.updated\",\"data\":{\"object\":"
                + "{\"id\":\"re_test_" + refundId + "\",\"status\":\"succeeded\",\"metadata\":{\"refundId\":\""
                + refundId + "\"}}}}";
        webhook(payload, sign(payload)).andExpect(status().isOk());

        assertThat(refund(refundId).get("status")).isEqualTo("SUCCEEDED");
        assertThat(outbox(KafkaTopics.REFUND_COMPLETED)).hasSize(1);
    }

    // ---------- outbox, listener, phân quyền ----------

    @Test
    void outboxPublishesJsonObjectKeyedByOrderId() throws Exception {
        paidPayment("42.50");
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));

        outboxScheduler.publishPendingEvents();

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.PAYMENT_SUCCEEDED), eq(orderId.toString()), message.capture());
        assertThat(message.getValue()).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) message.getValue()).path("orderId").stringValue()).isEqualTo(orderId.toString());
        assertThat(outbox(KafkaTopics.PAYMENT_SUCCEEDED).get(0).get("published_at")).isNotNull();
    }

    @Test
    void refundRequestedListenerIsRegistered() {
        // Thiếu spring-boot-starter-kafka thì @KafkaListener bị bỏ qua âm thầm (lỗi đã gặp ở order-service)
        assertThat(listenerRegistry.getListenerContainers()).anySatisfy(container -> {
            assertThat(container.getContainerProperties().getTopics())
                    .containsExactly(KafkaTopics.ORDER_REFUND_REQUESTED);
            assertThat(container.getGroupId()).isEqualTo("payment-service");
        });
    }

    @Test
    void internalCheckoutEndpointRequiresBuyerIdentityMatchingTheRequest() throws Exception {
        String body = jsonMapper.writeValueAsString(request("42.50"));

        mvc.perform(post("/internal/payments/checkout-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1003));
        mvc.perform(post("/internal/payments/checkout-sessions").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "BUYER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1004));
        mvc.perform(post("/internal/payments/checkout-sessions").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-User-Id", buyerId.toString()).header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1004));
        verify(stripeGateway, never()).createCheckoutSession(any());

        mvc.perform(post("/internal/payments/checkout-sessions").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-User-Id", buyerId.toString()).header("X-User-Role", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.result.checkoutUrl").isNotEmpty());
    }

    // ---------- helpers ----------

    private CreateCheckoutSessionRequest request(String amount) {
        return new CreateCheckoutSessionRequest(
                orderId, buyerId, new BigDecimal(amount), "USD", Instant.now().plus(10, ChronoUnit.MINUTES));
    }

    private void paidPayment(String amount) throws Exception {
        CheckoutSessionResponse session = paymentService.createCheckoutSession(request(amount));
        String payload = sessionEvent("evt_" + UUID.randomUUID(), "checkout.session.completed",
                "cs_test_" + session.getPaymentId(), "paid", "pi_test_1");
        webhook(payload, sign(payload)).andExpect(status().isOk());
    }

    private RefundRequestedEvent refundEvent(UUID refundId, String amount) {
        return new RefundRequestedEvent(refundId, orderId, buyerId, new BigDecimal(amount), "USD",
                "BUYER_REQUEST", Instant.now());
    }

    private void passDeadline() {
        jdbc.update("UPDATE payments SET expires_at = now() - interval '1 second' WHERE order_id = ?", orderId);
    }

    private ResultActions webhook(String payload, String signature) throws Exception {
        var request = post("/payments/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            request.header("Stripe-Signature", signature);
        }
        return mvc.perform(request);
    }

    private static String sessionEvent(String eventId, String type, String objectId, String paymentStatus, String intent) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + objectId + "\""
                + (paymentStatus == null ? "" : ",\"payment_status\":\"" + paymentStatus + "\"")
                + (intent == null ? "" : ",\"payment_intent\":\"" + intent + "\"")
                + "}}}";
    }

    /** Header Stripe-Signature theo đúng lược đồ của Stripe: HMAC-SHA256(secret, "timestamp.payload"). */
    private static String sign(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
    }

    private Map<String, Object> payment() {
        return jdbc.queryForMap("SELECT * FROM payments WHERE order_id = ?", orderId);
    }

    private Map<String, Object> refund(UUID refundId) {
        return jdbc.queryForMap("SELECT * FROM refunds WHERE id = ?", refundId);
    }

    private List<Map<String, Object>> outbox(String topic) {
        return jdbc.queryForList(
                "SELECT * FROM payment_event_outbox WHERE topic = ? AND message_key = ? ORDER BY created_at",
                topic, orderId.toString());
    }

    private int count(String sql, Object arg) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arg);
        return value == null ? 0 : value;
    }

    private static void assertError(ThrowableAssert.ThrowingCallable call, int code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }
}
