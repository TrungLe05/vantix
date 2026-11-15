package com.dev.order_service;

import com.dev.commonlib_kafka.event.PaymentFailedEvent;
import com.dev.commonlib_kafka.event.PaymentSucceededEvent;
import com.dev.commonlib_kafka.event.RefundCompletedEvent;
import com.dev.commonlib_kafka.event.RefundFailedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.order_service.client.PaymentClient;
import com.dev.order_service.client.PaymentFeignClient;
import com.dev.order_service.dto.request.OrderItemRequest;
import com.dev.order_service.dto.response.AvailabilityResponse;
import com.dev.order_service.dto.response.CheckoutResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.RefundQuoteResponse;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.enums.RefundReason;
import com.dev.order_service.enums.RefundStatus;
import com.dev.order_service.service.OrderCommandService;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P4 phía Order trên PostgreSQL/Redis thật: nhận kết quả thanh toán (kể cả lặp và đến trễ), khởi tạo
 * checkout, chính sách hoàn 3 mốc, hủy event hoàn 100%. Payment-service thay bằng mock
 * ({@link PaymentClient}); event Kafka được đưa thẳng vào handler như các test consumer khác.
 */
class OrderPaymentIntegrationTest extends AbstractOrderIT {

    @MockitoBean
    private PaymentClient paymentClient;

    @Autowired
    private OrderCommandService orderCommandService;

    // ---------- thanh toán thành công ----------

    @Test
    void paymentSucceededMarksOrderPaidAndMovesInventoryFromHeldToSold() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = pendingOrder(buyer, seat(0), ga(3));

        orderCommandService.handlePaymentSucceeded(succeeded(order, buyer));

        OrderResponse paid = orderService.getOrder(buyer, order.getId());
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.TICKETS_ISSUED);
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(paid.getRefund()).isNull();
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM holds WHERE order_id = ?", String.class,
                order.getId())).containsExactly("PURCHASED");
        assertThat(heldGa()).isZero();
        assertThat(soldGa()).isEqualTo(3);
        AvailabilityResponse availability = orderService.getAvailability(eventId);
        assertThat(availability.getSeats().stream().filter(s -> s.getSeatId().equals(seatIds.get(0)))
                .findFirst().orElseThrow().getStatus()).isEqualTo("SOLD");
    }

    @Test
    void duplicatePaymentSucceededIsIgnored() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = pendingOrder(buyer, ga(2));
        PaymentSucceededEvent event = succeeded(order, buyer);

        orderCommandService.handlePaymentSucceeded(event);
        orderCommandService.handlePaymentSucceeded(event);

        assertThat(orderStatus(order.getId())).isEqualTo("TICKETS_ISSUED");
        assertThat(soldGa()).isEqualTo(2);
        assertThat(heldGa()).isZero();
    }

    @Test
    void paidOrderIsNotTouchedByExpiryAndSoldSeatCannotBeHeldAgain() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = pendingOrder(buyer, seat(0));
        orderCommandService.handlePaymentSucceeded(succeeded(order, buyer));
        // Deadline hold trôi qua và key Redis của ghế hết TTL: chỉ còn ràng buộc DB giữ ghế đã bán
        forceExpire(order.getId());

        orderCommandService.expireDueOrders();

        assertThat(orderStatus(order.getId())).isEqualTo("TICKETS_ISSUED");
        assertError(() -> createCart(UUID.randomUUID(), key(), seat(0)), 4006);
        assertThat(activeSeatHolds(seatIds.get(0))).isEqualTo(1);
    }

    // ---------- thanh toán trễ / thất bại ----------

    @Test
    void paymentArrivingAfterExpiryRequestsFullRefundInsteadOfRevivingOrder() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = pendingOrder(buyer, seat(0), ga(2));
        forceExpire(order.getId());
        orderCommandService.expireDueOrders();
        PaymentSucceededEvent event = succeeded(order, buyer);

        orderCommandService.handlePaymentSucceeded(event);
        orderCommandService.handlePaymentSucceeded(event);

        // Order vẫn EXPIRED, vé đã nhả không bị lấy lại
        assertThat(orderStatus(order.getId())).isEqualTo("EXPIRED");
        assertThat(soldGa()).isZero();
        assertThat(activeSeatHolds(seatIds.get(0))).isZero();
        Map<String, Object> refund = refundRow(order.getId());
        assertThat(refund).containsEntry("reason", "LATE_PAYMENT").containsEntry("refund_percent", 100)
                .containsEntry("status", "REQUESTED");
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("190.00");
        // Đúng một yêu cầu hoàn trong outbox dù event đến hai lần
        assertThat(refundOutboxCount(order.getId())).isEqualTo(1);
        assertThat(orderService.getOrder(buyer, order.getId()).getRefund().getReason())
                .isEqualTo(RefundReason.LATE_PAYMENT);
    }

    @Test
    void paymentFailedReleasesHoldsOfPendingOrderOnly() {
        UUID buyer = UUID.randomUUID();
        OrderResponse expiredByPayment = pendingOrder(buyer, ga(2));
        OrderResponse paid = pendingOrder(UUID.randomUUID(), ga(1));
        orderCommandService.handlePaymentSucceeded(succeeded(paid, UUID.randomUUID()));

        orderCommandService.handlePaymentFailed(new PaymentFailedEvent(UUID.randomUUID(),
                expiredByPayment.getId(), buyer, PaymentFailedEvent.REASON_EXPIRED, Instant.now()));
        // payment.failed lạc tới Order đã PAID không được nhả vé đã bán
        orderCommandService.handlePaymentFailed(new PaymentFailedEvent(UUID.randomUUID(),
                paid.getId(), buyer, PaymentFailedEvent.REASON_FAILED, Instant.now()));

        assertThat(orderStatus(expiredByPayment.getId())).isEqualTo("EXPIRED");
        assertThat(orderStatus(paid.getId())).isEqualTo("TICKETS_ISSUED");
        assertThat(heldGa()).isZero();
        assertThat(soldGa()).isEqualTo(1);
    }

    // ---------- checkout (OR09) và hủy (OR08) ----------

    @Test
    void checkoutReturnsPaymentLinkWithHoldDeadlineForPendingOrder() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = pendingOrder(buyer, ga(2));
        when(paymentClient.createCheckoutSession(eq(order.getId()), eq(buyer), any(), eq("USD"), any()))
                .thenReturn(new PaymentFeignClient.CheckoutSession(UUID.randomUUID(), order.getId(), "PENDING",
                        "https://checkout.stripe.test/session", order.getHoldExpiresAt()));

        CheckoutResponse checkout = orderService.checkout(buyer, order.getId());

        assertThat(checkout.getCheckoutUrl()).isEqualTo("https://checkout.stripe.test/session");
        assertThat(checkout.getExpiresAt()).isEqualTo(orderService.getOrder(buyer, order.getId()).getHoldExpiresAt());
    }

    @Test
    void checkoutIsRejectedForCartExpiredAndForeignOrders() {
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), ga(1));
        assertError(() -> orderService.checkout(buyer, cart.getId()), 4005);
        assertError(() -> orderService.checkout(UUID.randomUUID(), cart.getId()), 4001);

        orderService.confirm(buyer, cart.getId(), key());
        forceExpire(cart.getId());
        assertError(() -> orderService.checkout(buyer, cart.getId()), 4004);

        verifyNoInteractions(paymentClient);
    }

    @Test
    void pendingPaymentOrderCannotBeCancelledByBuyer() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = pendingOrder(buyer, ga(2));

        assertError(() -> orderService.cancel(buyer, order.getId()), 4005);

        assertThat(orderStatus(order.getId())).isEqualTo("PENDING_PAYMENT");
        assertThat(heldGa()).isEqualTo(2);
    }

    // ---------- hoàn tiền ----------

    @Test
    void refundSevenDaysOrMoreBeforeEventReturnsFullAmountAndTicketsToStock() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, seat(0), ga(3));

        RefundQuoteResponse quote = orderService.getRefundQuote(buyer, order.getId());
        assertThat(quote.isRefundable()).isTrue();
        assertThat(quote.getPercent()).isEqualTo(100);
        assertThat(quote.getAmount()).isEqualByComparingTo("210.00");

        OrderResponse refunding = orderService.requestRefund(buyer, order.getId());
        // Bấm lần hai không tạo thêm yêu cầu hoàn
        orderService.requestRefund(buyer, order.getId());

        assertThat(refunding.getStatus()).isEqualTo(OrderStatus.REFUND_PENDING);
        assertThat(refunding.getRefund().getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refunding.getRefund().getAmount()).isEqualByComparingTo("210.00");
        assertThat(soldGa()).isZero();
        assertThat(activeSeatHolds(seatIds.get(0))).isZero();
        assertThat(refundOutboxCount(order.getId())).isEqualTo(1);
        // Vé trả về kho: buyer khác giữ được ngay
        assertThat(createCart(UUID.randomUUID(), key(), seat(0)).getStatus()).isEqualTo(OrderStatus.CART);
    }

    @Test
    void refundBetweenOneAndSevenDaysBeforeEventReturnsHalf() {
        eventStartAt = Instant.now().plus(3, ChronoUnit.DAYS);
        catalog(4, 10);
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, seat(0), ga(1));   // 170.00

        assertThat(orderService.getRefundQuote(buyer, order.getId()).getPercent()).isEqualTo(50);
        OrderResponse refunding = orderService.requestRefund(buyer, order.getId());

        assertThat(refunding.getRefund().getPercent()).isEqualTo(50);
        assertThat(refunding.getRefund().getAmount()).isEqualByComparingTo("85.00");
    }

    @Test
    void refundWithinTwentyFourHoursOfEventIsRejectedAndOrderStaysPaid() {
        eventStartAt = Instant.now().plus(12, ChronoUnit.HOURS);
        catalog(4, 10);
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, ga(2));

        RefundQuoteResponse quote = orderService.getRefundQuote(buyer, order.getId());
        assertThat(quote.isRefundable()).isFalse();
        assertThat(quote.getAmount()).isEqualByComparingTo("0.00");
        assertError(() -> orderService.requestRefund(buyer, order.getId()), 4015);

        assertThat(orderStatus(order.getId())).isEqualTo("TICKETS_ISSUED");
        assertThat(soldGa()).isEqualTo(2);
        assertThat(refundOutboxCount(order.getId())).isZero();
    }

    @Test
    void unpaidOrderCannotBeRefunded() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = pendingOrder(buyer, ga(1));

        assertThat(orderService.getRefundQuote(buyer, order.getId()).isRefundable()).isFalse();
        assertError(() -> orderService.requestRefund(buyer, order.getId()), 4015);
        assertError(() -> orderService.requestRefund(UUID.randomUUID(), order.getId()), 4001);
    }

    @Test
    void refundCompletedMovesOrderToRefundedAndIsIdempotent() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, ga(2));
        orderService.requestRefund(buyer, order.getId());
        UUID refundId = (UUID) refundRow(order.getId()).get("id");
        RefundCompletedEvent completed = new RefundCompletedEvent(
                refundId, order.getId(), new BigDecimal("40.00"), "USD", Instant.now());

        orderCommandService.handleRefundCompleted(completed);
        orderCommandService.handleRefundCompleted(completed);
        // refund.failed đến sau khi đã hoàn xong không được lật ngược trạng thái
        orderCommandService.handleRefundFailed(new RefundFailedEvent(refundId, order.getId(), "late", Instant.now()));

        OrderResponse refunded = orderService.getOrder(buyer, order.getId());
        assertThat(refunded.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(refunded.getRefund().getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refunded.getRefund().getCompletedAt()).isNotNull();
    }

    @Test
    void refundFailedKeepsOrderPendingForManualHandling() {
        UUID buyer = UUID.randomUUID();
        OrderResponse order = paidOrder(buyer, ga(1));
        orderService.requestRefund(buyer, order.getId());
        UUID refundId = (UUID) refundRow(order.getId()).get("id");

        orderCommandService.handleRefundFailed(
                new RefundFailedEvent(refundId, order.getId(), "charge_already_refunded", Instant.now()));

        OrderResponse failed = orderService.getOrder(buyer, order.getId());
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.REFUND_PENDING);
        assertThat(failed.getRefund().getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refundRow(order.getId())).containsEntry("failure_reason", "charge_already_refunded");
    }

    @Test
    void cancellingEventRefundsPaidOrdersInFullExactlyOnce() {
        // Sát giờ diễn (mốc 0% nếu buyer tự hủy): hủy event vẫn phải hoàn 100%
        eventStartAt = Instant.now().plus(2, ChronoUnit.HOURS);
        catalog(4, 10);
        UUID buyer = UUID.randomUUID();
        OrderResponse paid = paidOrder(buyer, seat(0), ga(2));     // 190.00
        OrderResponse pending = pendingOrder(UUID.randomUUID(), ga(1));

        orderCommandService.cancelUnpaidOrdersForEvent(eventId);
        orderCommandService.cancelUnpaidOrdersForEvent(eventId);

        assertThat(orderStatus(paid.getId())).isEqualTo("REFUND_PENDING");
        assertThat(orderStatus(pending.getId())).isEqualTo("CANCELLED");
        Map<String, Object> refund = refundRow(paid.getId());
        assertThat(refund).containsEntry("reason", "EVENT_CANCELLED").containsEntry("refund_percent", 100);
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("190.00");
        assertThat(refundOutboxCount(paid.getId())).isEqualTo(1);
        assertThat(soldGa()).isZero();
        assertThat(heldGa()).isZero();
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

    private static PaymentSucceededEvent succeeded(OrderResponse order, UUID buyer) {
        return new PaymentSucceededEvent(UUID.randomUUID(), order.getId(), buyer,
                order.getTotalAmount(), "USD", Instant.now());
    }

    private int soldGa() {
        Integer sold = jdbc.queryForObject(
                "SELECT COALESCE(max(sold_quantity), 0) FROM ticket_inventory WHERE ticket_type_id = ?",
                Integer.class, gaTypeId);
        return sold == null ? 0 : sold;
    }

    private Map<String, Object> refundRow(UUID orderId) {
        return jdbc.queryForMap("SELECT * FROM order_refunds WHERE order_id = ?", orderId);
    }

    private int refundOutboxCount(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM order_event_outbox WHERE order_id = ? AND topic = ? AND payload IS NOT NULL",
                Integer.class, orderId, KafkaTopics.ORDER_REFUND_REQUESTED);
    }

    private static void assertError(ThrowableAssert.ThrowingCallable call, int code) {
        assertThatThrownBy(call).satisfies(e -> assertThat(new Outcome<>(null, e).errorCode()).isEqualTo(code));
    }
}
