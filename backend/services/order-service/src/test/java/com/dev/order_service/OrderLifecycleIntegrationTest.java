package com.dev.order_service;

import com.dev.order_service.dto.request.AddOrderItemsRequest;
import com.dev.order_service.dto.response.AvailabilityResponse;
import com.dev.order_service.dto.response.OrderItemResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.service.OrderCommandService;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Vòng đời Order và các chuyển trạng thái của OrderCommandService trên DB thật:
 * tạo hold → thêm → sửa GA → confirm → hết hạn / hủy event, cùng các chuyển trạng thái bị cấm.
 * Kiểm thử ở mức integration (không mock repository) vì chuyển trạng thái phụ thuộc khóa và
 * update có điều kiện ở PostgreSQL.
 */
class OrderLifecycleIntegrationTest extends AbstractOrderIT {

    @Autowired
    private OrderCommandService orderCommandService;

    @Test
    void fullHappyPathKeepsTotalsInventoryAndDeadlineConsistent() {
        UUID buyer = UUID.randomUUID();

        OrderResponse cart = createCart(buyer, key(), seat(0));
        assertThat(cart.getStatus()).isEqualTo(OrderStatus.CART);
        assertThat(cart.getTotalAmount()).isEqualByComparingTo("150.00");

        OrderResponse added = orderService.addItems(buyer, cart.getId(),
                new AddOrderItemsRequest(List.of(ga(2), seat(1))), key());
        // Thêm item không gia hạn (so tới mili giây: response đầu mang độ chính xác nano của Java, DB lưu micro)
        assertThat(added.getHoldExpiresAt()).isCloseTo(cart.getHoldExpiresAt(), within(1, ChronoUnit.MILLIS));
        assertThat(added.getTotalAmount()).isEqualByComparingTo("340.00");
        assertThat(heldGa()).isEqualTo(2);

        UUID gaItem = gaItemId(added);
        OrderResponse increased = orderService.updateItemQuantity(buyer, cart.getId(), gaItem, 5);
        assertThat(increased.getTotalAmount()).isEqualByComparingTo("400.00");
        assertThat(heldGa()).isEqualTo(5).isEqualTo(sumActiveGaHolds());

        OrderResponse decreased = orderService.updateItemQuantity(buyer, cart.getId(), gaItem, 1);
        assertThat(decreased.getTotalAmount()).isEqualByComparingTo("320.00");
        assertThat(heldGa()).isEqualTo(1).isEqualTo(sumActiveGaHolds());

        AvailabilityResponse availability = orderService.getAvailability(eventId);
        assertThat(seatStatus(availability, 0)).isEqualTo("HELD");
        assertThat(seatStatus(availability, 2)).isEqualTo("AVAILABLE");
        assertThat(gaAvailable(availability)).isEqualTo(9);

        OrderResponse confirmed = orderService.confirm(buyer, cart.getId(), key());
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM holds WHERE order_id = ?", String.class,
                cart.getId())).containsExactly("CONFIRMED");
        // Outbox ghi cùng transaction cho mỗi lần thêm vé (tạo, thêm, tăng số lượng)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_event_outbox WHERE order_id = ?",
                Integer.class, cart.getId())).isEqualTo(3);
    }

    @Test
    void secondCreateForSameEventAppendsToExistingCart() {
        UUID buyer = UUID.randomUUID();
        OrderResponse first = createCart(buyer, key(), seat(0));
        OrderResponse second = createCart(buyer, key(), ga(1));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getItems()).hasSize(2);
    }

    @Test
    void expiredCartReleasesSeatAndGaAndCannotBeConfirmed() {
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), seat(0), ga(3));
        forceExpire(cart.getId());

        assertThat(orderService.getOrder(buyer, cart.getId()).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertError(() -> orderService.confirm(buyer, cart.getId(), key()), 4004);
        assertThat(heldGa()).isZero();
        assertThat(activeSeatHolds(seatIds.get(0))).isZero();
        assertThat(redis.hasKey("order:hold:seat:" + seatIds.get(0))).isFalse();
        assertThat(orderService.getOrders(buyer)).isEmpty();
    }

    @Test
    void expiredCartIsReplacedByNewCartOnNextCreate() {
        UUID buyer = UUID.randomUUID();
        OrderResponse old = createCart(buyer, key(), seat(0));
        forceExpire(old.getId());

        OrderResponse fresh = createCart(buyer, key(), seat(0));

        assertThat(fresh.getId()).isNotEqualTo(old.getId());
        assertThat(orderStatus(old.getId())).isEqualTo("EXPIRED");
        assertThat(activeSeatHolds(seatIds.get(0))).isEqualTo(1);
    }

    @Test
    void schedulerExpiresDueOrdersInBatch() {
        OrderResponse a = createCart(UUID.randomUUID(), key(), ga(1));
        OrderResponse b = createCart(UUID.randomUUID(), key(), ga(2));
        forceExpire(a.getId());
        forceExpire(b.getId());

        orderCommandService.expireDueOrders();

        assertThat(orderStatus(a.getId())).isEqualTo("EXPIRED");
        assertThat(orderStatus(b.getId())).isEqualTo("EXPIRED");
        assertThat(heldGa()).isZero();
    }

    // ---------- chuyển trạng thái bị cấm ----------

    @Test
    void pendingPaymentOrderIsFrozenForCartOperations() {
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), seat(0), ga(2));
        orderService.confirm(buyer, cart.getId(), key());
        UUID gaItem = gaItemId(cart);

        assertError(() -> orderService.addItems(buyer, cart.getId(),
                new AddOrderItemsRequest(List.of(ga(1))), key()), 4005);
        assertError(() -> orderService.updateItemQuantity(buyer, cart.getId(), gaItem, 3), 4005);
        assertError(() -> orderService.removeItem(buyer, cart.getId(), gaItem), 4005);
        assertError(() -> orderService.confirm(buyer, cart.getId(), key()), 4005);
    }

    @Test
    void confirmReplayWithSameKeyIsIdempotent() {
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), ga(1));
        String key = key();

        OrderResponse first = orderService.confirm(buyer, cart.getId(), key);
        OrderResponse replay = orderService.confirm(buyer, cart.getId(), key);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void cancelReleasesHoldsAndIsIdempotent() {
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), seat(0), ga(2));
        orderService.confirm(buyer, cart.getId(), key());

        assertThat(orderService.cancel(buyer, cart.getId()).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderService.cancel(buyer, cart.getId()).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(heldGa()).isZero();
        assertThat(activeSeatHolds(seatIds.get(0))).isZero();
    }

    @Test
    void removingLastItemCancelsCartAndRemovingTwiceReleasesOnce() {
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), ga(2));
        UUID gaItem = gaItemId(cart);
        createCart(UUID.randomUUID(), key(), ga(3)); // buyer khác giữ 3 vé, không được bị nhả nhầm

        assertThat(orderService.removeItem(buyer, cart.getId(), gaItem).getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertError(() -> orderService.removeItem(buyer, cart.getId(), gaItem), 4005);
        assertThat(heldGa()).isEqualTo(3);
    }

    @Test
    void ordersAreInvisibleToOtherBuyers() {
        OrderResponse cart = createCart(UUID.randomUUID(), key(), ga(1));
        UUID stranger = UUID.randomUUID();

        assertError(() -> orderService.getOrder(stranger, cart.getId()), 4001);
        assertError(() -> orderService.cancel(stranger, cart.getId()), 4001);
        assertError(() -> orderService.confirm(stranger, cart.getId(), key()), 4001);
    }

    @Test
    void quantityUpdateValidatesTargetAndCategory() {
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), seat(0), ga(2));
        UUID seatItem = cart.getItems().stream().filter(i -> "SEATED".equals(i.getCategory()))
                .findFirst().orElseThrow().getId();

        assertError(() -> orderService.updateItemQuantity(buyer, cart.getId(), gaItemId(cart), 0), 4007);
        assertError(() -> orderService.updateItemQuantity(buyer, cart.getId(), seatItem, 2), 4007);
        assertError(() -> orderService.updateItemQuantity(buyer, cart.getId(), gaItemId(cart), 11), 4006);
        assertThat(heldGa()).isEqualTo(2);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        assertError(() -> createCart(UUID.randomUUID(), null, ga(1)), 4011);
        assertError(() -> createCart(UUID.randomUUID(), " ", ga(1)), 4011);
        assertError(() -> createCart(UUID.randomUUID(), "k".repeat(129), ga(1)), 4011);
    }

    // ---------- helpers ----------

    private static UUID gaItemId(OrderResponse order) {
        return order.getItems().stream()
                .filter(i -> "GENERAL_ADMISSION".equals(i.getCategory()) && "ACTIVE".equals(i.getStatus()))
                .map(OrderItemResponse::getId)
                .findFirst().orElseThrow();
    }

    private String seatStatus(AvailabilityResponse availability, int index) {
        return availability.getSeats().stream()
                .filter(s -> s.getSeatId().equals(seatIds.get(index)))
                .findFirst().orElseThrow().getStatus();
    }

    private int gaAvailable(AvailabilityResponse availability) {
        return availability.getTicketTypes().stream()
                .filter(t -> t.getTicketTypeId().equals(gaTypeId))
                .findFirst().orElseThrow().getAvailableQuantity();
    }

    private static void assertError(ThrowableAssert.ThrowingCallable call, int code) {
        assertThatThrownBy(call).satisfies(e -> assertThat(new Outcome<>(null, e).errorCode()).isEqualTo(code));
    }
}
