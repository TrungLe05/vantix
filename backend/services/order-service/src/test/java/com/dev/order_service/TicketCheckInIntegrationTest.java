package com.dev.order_service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_kafka.event.PaymentSucceededEvent;
import com.dev.commonlib_kafka.event.RefundCompletedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.order_service.dto.request.OrderItemRequest;
import com.dev.order_service.dto.response.CheckInResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.TicketResponse;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.enums.TicketStatus;
import com.dev.order_service.service.OrderCommandService;
import com.dev.order_service.service.TicketService;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import jakarta.servlet.Filter;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P5 phía Order trên PostgreSQL/Redis thật: phát hành vé khi thanh toán, ảnh QR, check-in (kể cả
 * quét trùng song song), hủy vé khi hoàn tiền, event order.tickets-issued / order.refunded.
 */
class TicketCheckInIntegrationTest extends AbstractOrderIT {

    @Autowired
    private OrderCommandService orderCommandService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    // ---------- phát hành vé ----------

    @Test
    void paymentIssuesOneTicketPerSeatAndPerGaUnitWithUniqueCodes() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, seat(0), ga(3));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.TICKETS_ISSUED);
        assertThat(order.getTickets()).hasSize(4)
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TicketStatus.VALID));
        assertThat(order.getTickets().stream().map(TicketResponse::getCode).distinct()).hasSize(4);
        assertThat(order.getTickets()).filteredOn(t -> t.getSeatLabel() != null).hasSize(1);
        assertThat(order.getTickets().get(0).getCode()).hasSize(32);
        // Một event order.tickets-issued trong outbox, không chứa mã vé
        List<Map<String, Object>> events = outbox(order.getId(), KafkaTopics.ORDER_TICKETS_ISSUED);
        assertThat(events).hasSize(1);
        String payload = (String) events.get(0).get("payload");
        assertThat(payload).contains("\"ticketCount\":4");
        order.getTickets().forEach(t -> assertThat(payload).doesNotContain(t.getCode()));
    }

    @Test
    void duplicatePaymentEventDoesNotIssueTicketsTwice() {
        UUID buyer = UUID.randomUUID();
        OrderResponse pending = pendingOrder(buyer, ga(2));
        PaymentSucceededEvent event = succeeded(pending, buyer);

        orderCommandService.handlePaymentSucceeded(event);
        orderCommandService.handlePaymentSucceeded(event);

        assertThat(ticketCount(pending.getId())).isEqualTo(2);
        assertThat(outbox(pending.getId(), KafkaTopics.ORDER_TICKETS_ISSUED)).hasSize(1);
    }

    // ---------- QR ----------

    @Test
    void qrImageEncodesTheTicketCodeAndIsOnlyForTheOwner() throws Exception {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, ga(1));
        TicketResponse ticket = order.getTickets().get(0);
        String url = "/orders/" + order.getId() + "/tickets/" + ticket.getId() + "/qr";

        byte[] png = mvc.perform(get(url).header("X-User-Id", buyer.toString()).header("X-User-Role", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(decodeQr(png)).isEqualTo(ticket.getCode());

        mvc.perform(get(url).header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "BUYER"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(4001));
    }

    // ---------- check-in ----------

    @Test
    void firstScanChecksInAndSecondScanReportsWhoAndWhen() throws Exception {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, seat(0));
        String code = order.getTickets().get(0).getCode();
        UUID staff = UUID.randomUUID();

        mvc.perform(checkIn(staff, "STAFF", eventId, "  " + code + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result.ticketId").value(order.getTickets().get(0).getId().toString()))
                .andExpect(jsonPath("$.result.seatLabel").value("Floor · A-01"))
                .andExpect(jsonPath("$.result.checkedInBy").value(staff.toString()));

        // Lần quét thứ hai (cổng khác, người khác): bị từ chối, trả lại thông tin lần đầu
        mvc.perform(checkIn(UUID.randomUUID(), "ADMIN", eventId, code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4017))
                .andExpect(jsonPath("$.result.checkedInBy").value(staff.toString()))
                .andExpect(jsonPath("$.result.checkedInAt").isNotEmpty());

        assertThat(orderService.getOrder(buyer, order.getId()).getTickets().get(0).getStatus())
                .isEqualTo(TicketStatus.CHECKED_IN);
    }

    @Test
    void concurrentScansOfTheSameTicketLetExactlyOneThrough() throws Exception {
        OrderResponse order = paidOrder(UUID.randomUUID(), ga(1));
        String code = order.getTickets().get(0).getCode();

        List<Outcome<CheckInResponse>> outcomes = runConcurrently(10,
                () -> ticketService.checkIn(UUID.randomUUID(), eventId, code));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded()).hasSize(9)
                .allSatisfy(o -> assertThat(o.errorCode()).isEqualTo(4017));
    }

    @Test
    void unknownCodeAndTicketOfAnotherEventAreBothNotFound() throws Exception {
        OrderResponse order = paidOrder(UUID.randomUUID(), ga(1));
        String code = order.getTickets().get(0).getCode();

        mvc.perform(checkIn(UUID.randomUUID(), "STAFF", eventId, "does-not-exist"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(4016));
        mvc.perform(checkIn(UUID.randomUUID(), "STAFF", UUID.randomUUID(), code))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(4016));
        // Quét sai event không được làm hỏng vé ở event đúng
        assertThat(ticketService.checkIn(UUID.randomUUID(), eventId, code).getTicketId())
                .isEqualTo(order.getTickets().get(0).getId());
    }

    @Test
    void onlyAdminAndStaffCanScanAndRequestIsValidated() throws Exception {
        OrderResponse order = paidOrder(UUID.randomUUID(), ga(1));
        String code = order.getTickets().get(0).getCode();

        mvc.perform(checkIn(UUID.randomUUID(), "BUYER", eventId, code))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1004));
        mvc.perform(post("/orders/tickets/check-in").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1003));
        mvc.perform(checkIn(UUID.randomUUID(), "STAFF", eventId, " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));

        assertThat(orderService.getOrder(buyerOf(order), order.getId())
                .getTickets().get(0).getStatus()).isEqualTo(TicketStatus.VALID);
    }

    // ---------- hoàn tiền và vé ----------

    @Test
    void refundVoidsTicketsSoTheyCannotBeScannedAndPublishesOrderRefunded() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, ga(2));
        String code = order.getTickets().get(0).getCode();

        orderService.requestRefund(buyer, order.getId());

        assertThat(orderService.getOrder(buyer, order.getId()).getTickets())
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TicketStatus.VOID));
        assertError(() -> ticketService.checkIn(UUID.randomUUID(), eventId, code), 4018);
        assertError(() -> ticketService.qrPng(buyer, order.getId(), order.getTickets().get(0).getId()), 4018);

        UUID refundId = jdbc.queryForObject("SELECT id FROM order_refunds WHERE order_id = ?", UUID.class, order.getId());
        RefundCompletedEvent completed = new RefundCompletedEvent(
                refundId, order.getId(), new BigDecimal("40.00"), "USD", Instant.now());
        orderCommandService.handleRefundCompleted(completed);
        orderCommandService.handleRefundCompleted(completed);

        List<Map<String, Object>> refunded = outbox(order.getId(), KafkaTopics.ORDER_REFUNDED);
        assertThat(refunded).hasSize(1);
        assertThat((String) refunded.get(0).get("payload")).contains("\"reason\":\"BUYER_REQUEST\"")
                .contains(buyer.toString());
    }

    @Test
    void orderWithACheckedInTicketCannotBeRefundedButEventCancellationStillRefunds() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, ga(2));
        ticketService.checkIn(UUID.randomUUID(), eventId, order.getTickets().get(0).getCode());

        assertThat(orderService.getRefundQuote(buyer, order.getId()).isRefundable()).isFalse();
        assertError(() -> orderService.requestRefund(buyer, order.getId()), 4015);
        assertThat(orderStatus(order.getId())).isEqualTo("TICKETS_ISSUED");

        // ADMIN hủy event: vẫn hoàn 100% (D16); vé đã check-in giữ làm lịch sử, vé còn lại VOID
        orderCommandService.cancelUnpaidOrdersForEvent(eventId);

        assertThat(orderStatus(order.getId())).isEqualTo("REFUND_PENDING");
        assertThat(orderService.getOrder(buyer, order.getId()).getTickets())
                .extracting(TicketResponse::getStatus)
                .containsExactlyInAnyOrder(TicketStatus.CHECKED_IN, TicketStatus.VOID);
    }

    // ---------- helpers ----------

    private OrderResponse pendingOrder(UUID buyer, OrderItemRequest... items) {
        OrderResponse cart = createCart(buyer, key(), items);
        return orderService.confirm(buyer, cart.getId(), key());
    }

    private OrderResponse paidOrder(UUID buyer, OrderItemRequest... items) {
        OrderResponse order = pendingOrder(buyer, items);
        orderCommandService.handlePaymentSucceeded(succeeded(order, buyer));
        return orderService.getOrder(buyer, order.getId());
    }

    private UUID buyerOf(OrderResponse order) {
        return jdbc.queryForObject("SELECT buyer_id FROM orders WHERE id = ?", UUID.class, order.getId());
    }

    private static PaymentSucceededEvent succeeded(OrderResponse order, UUID buyer) {
        return new PaymentSucceededEvent(UUID.randomUUID(), order.getId(), buyer,
                order.getTotalAmount(), "USD", Instant.now());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder checkIn(
            UUID userId, String role, UUID eventId, String code) {
        return post("/orders/tickets/check-in")
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"" + eventId + "\",\"code\":\"" + code + "\"}");
    }

    private int ticketCount(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM tickets WHERE order_id = ?", Integer.class, orderId);
    }

    private List<Map<String, Object>> outbox(UUID orderId, String topic) {
        return jdbc.queryForList("SELECT * FROM order_event_outbox WHERE order_id = ? AND topic = ?", orderId, topic);
    }

    private static String decodeQr(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(image)))).getText();
    }

    private static void assertError(ThrowableAssert.ThrowingCallable call, int code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }
}
