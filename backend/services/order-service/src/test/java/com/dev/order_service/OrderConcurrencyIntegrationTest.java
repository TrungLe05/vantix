package com.dev.order_service;

import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.event.OrderEventListener;
import com.dev.order_service.service.OrderCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chống bán trùng (D08) trên PostgreSQL/Redis thật: tranh ghế, tranh GA, idempotency,
 * hết hạn nhả tồn đúng một lần, consumer hủy event chạy lặp.
 */
class OrderConcurrencyIntegrationTest extends AbstractOrderIT {

    @Autowired
    private OrderCommandService orderCommandService;

    @Autowired
    private OrderEventListener orderEventListener;

    // ---------- Tranh ghế ----------

    @Test
    void manyBuyersRacingForOneSeatProduceExactlyOneHold() throws InterruptedException {
        List<Outcome<OrderResponse>> outcomes = runConcurrently(20,
                () -> createCart(UUID.randomUUID(), key(), seat(0)));

        List<Outcome<OrderResponse>> winners = outcomes.stream().filter(Outcome::succeeded).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allSatisfy(o -> assertThat(o.errorCode()).isEqualTo(4006));
        assertThat(activeSeatHolds(seatIds.get(0))).isEqualTo(1);
        // Lớp Redis trỏ về đúng Order thắng
        assertThat(redis.opsForValue().get("order:hold:seat:" + seatIds.get(0)))
                .isEqualTo(winners.get(0).value().getId().toString());
    }

    @Test
    void overlappingSeatRequestsAreAllOrNothing() throws InterruptedException {
        // Mỗi buyer xin 2 ghế liền kề (0-1, 1-2, 2-3): request nào trùng ghế với request thắng phải thất bại trọn gói
        List<Callable<OrderResponse>> tasks = List.of(
                () -> createCart(UUID.randomUUID(), key(), seat(0), seat(1)),
                () -> createCart(UUID.randomUUID(), key(), seat(1), seat(2)),
                () -> createCart(UUID.randomUUID(), key(), seat(2), seat(3)));

        runConcurrently(tasks);

        for (UUID seatId : seatIds) {
            assertThat(activeSeatHolds(seatId)).isLessThanOrEqualTo(1);
        }
        // Không có Order nào giữ "một nửa" yêu cầu
        List<Integer> itemsPerCart = jdbc.queryForList(
                "SELECT count(*) FROM holds h JOIN orders o ON o.id = h.order_id " +
                        "WHERE o.event_id = ? AND h.status = 'HELD' GROUP BY o.id", Integer.class, eventId);
        assertThat(itemsPerCart).isNotEmpty().allMatch(n -> n == 2);
    }

    // ---------- Tranh GA ----------

    @Test
    void gaContentionNeverExceedsCapacity() throws InterruptedException {
        catalog(0, 10);

        List<Outcome<OrderResponse>> outcomes = runConcurrently(30,
                () -> createCart(UUID.randomUUID(), key(), ga(1)));

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(10);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allSatisfy(o -> assertThat(o.errorCode()).isEqualTo(4006));
        assertThat(heldGa()).isEqualTo(10);
        assertThat(sumActiveGaHolds()).isEqualTo(10);
    }

    @Test
    void gaContentionWithMultiTicketRequestsStaysWithinCapacity() throws InterruptedException {
        catalog(0, 10);

        List<Outcome<OrderResponse>> outcomes = runConcurrently(12,
                () -> createCart(UUID.randomUUID(), key(), ga(3)));

        // 10 vé / 3 vé mỗi request → tối đa 3 request thắng, phần dư 1 vé không bị giữ lẻ
        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(3);
        assertThat(heldGa()).isEqualTo(9);
        assertThat(sumActiveGaHolds()).isEqualTo(9);
    }

    @Test
    void concurrentQuantityIncreasesOnSameCartStayWithinCapacity() throws InterruptedException {
        catalog(0, 10);
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), ga(2));
        UUID itemId = cart.getItems().get(0).getId();

        // Hai buyer khác cũng đang tranh phần còn lại
        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> orderService.updateItemQuantity(buyer, cart.getId(), itemId, 6));
        tasks.add(() -> createCart(UUID.randomUUID(), key(), ga(4)));
        tasks.add(() -> createCart(UUID.randomUUID(), key(), ga(4)));
        runConcurrently(tasks);

        assertThat(heldGa()).isLessThanOrEqualTo(10).isEqualTo(sumActiveGaHolds());
    }

    // ---------- Idempotency ----------

    @Test
    void replayWithSameKeyAndBodyReturnsSameOrderWithoutSecondHold() {
        catalog(0, 10);
        UUID buyer = UUID.randomUUID();
        String key = key();

        OrderResponse first = createCart(buyer, key, ga(2));
        OrderResponse replay = createCart(buyer, key, ga(2));

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(heldGa()).isEqualTo(2);
    }

    @Test
    void concurrentRetriesWithSameKeyCreateOneOrder() throws InterruptedException {
        catalog(0, 10);
        UUID buyer = UUID.randomUUID();
        String key = key();

        List<Outcome<OrderResponse>> outcomes = runConcurrently(10, () -> createCart(buyer, key, ga(2)));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().map(o -> o.value().getId()).distinct()).hasSize(1);
        assertThat(heldGa()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsConflict() {
        catalog(0, 10);
        UUID buyer = UUID.randomUUID();
        String key = key();
        createCart(buyer, key, ga(2));

        assertThatThrownBy(() -> createCart(buyer, key, ga(3)))
                .satisfies(e -> assertThat(new Outcome<>(null, e).errorCode()).isEqualTo(4008));
        assertThat(heldGa()).isEqualTo(2);
    }

    @Test
    void sameKeyFromAnotherBuyerIsIndependent() {
        catalog(0, 10);
        String key = key();

        OrderResponse a = createCart(UUID.randomUUID(), key, ga(1));
        OrderResponse b = createCart(UUID.randomUUID(), key, ga(1));

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(heldGa()).isEqualTo(2);
    }

    // ---------- Hết hạn nhả tồn đúng một lần ----------

    @Test
    void expiryReleasesInventoryExactlyOnceUnderConcurrentExpirers() throws InterruptedException {
        catalog(2, 10);
        UUID expiringBuyer = UUID.randomUUID();
        OrderResponse expiring = createCart(expiringBuyer, key(), seat(0), ga(3));
        OrderResponse untouched = createCart(UUID.randomUUID(), key(), ga(2));
        forceExpire(expiring.getId());

        // Job định kỳ, đọc danh sách, đọc chi tiết và availability cùng phát hiện hết hạn một lúc
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(orderCommandService::expireDueOrders);
            tasks.add(() -> orderService.getOrder(expiringBuyer, expiring.getId()));
            tasks.add(() -> orderService.getOrders(expiringBuyer));
            tasks.add(() -> orderService.getAvailability(eventId));
        }
        List<Outcome<Object>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(orderStatus(expiring.getId())).isEqualTo("EXPIRED");
        // Chỉ nhả 3 vé của Order hết hạn — nhả hai lần sẽ ăn vào 2 vé của Order còn hạn
        assertThat(heldGa()).isEqualTo(2).isEqualTo(sumActiveGaHolds());
        assertThat(activeSeatHolds(seatIds.get(0))).isZero();
        assertThat(orderStatus(untouched.getId())).isEqualTo("CART");
    }

    @Test
    void expiredSeatCanBeHeldAgainImmediately() {
        UUID first = UUID.randomUUID();
        OrderResponse expiring = createCart(first, key(), seat(0));
        forceExpire(expiring.getId());
        orderCommandService.expireDueOrders();

        OrderResponse next = createCart(UUID.randomUUID(), key(), seat(0));

        assertThat(next.getStatus()).isEqualTo(OrderStatus.CART);
        assertThat(activeSeatHolds(seatIds.get(0))).isEqualTo(1);
        assertThat(redis.opsForValue().get("order:hold:seat:" + seatIds.get(0))).isEqualTo(next.getId().toString());
    }

    // ---------- Consumer hủy event lặp ----------

    @Test
    void duplicateEventCancelledMessagesReleaseOnce() throws InterruptedException {
        catalog(2, 10);
        OrderResponse cart = createCart(UUID.randomUUID(), key(), seat(0), ga(4));
        UUID pendingBuyer = UUID.randomUUID();
        OrderResponse pending = createCart(pendingBuyer, key(), ga(1));
        orderService.confirm(pendingBuyer, pending.getId(), key());
        String payload = "{\"eventId\":\"" + eventId + "\",\"cancelledAt\":\"" + Instant.now() + "\"}";

        List<Outcome<Object>> outcomes = runConcurrently(5, () -> {
            orderEventListener.onEventCancelled(payload);
            return null;
        });
        orderEventListener.onEventCancelled(payload); // giao lại sau cùng (at-least-once)

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(orderStatus(cart.getId())).isEqualTo("CANCELLED");
        assertThat(orderStatus(pending.getId())).isEqualTo("CANCELLED");
        assertThat(heldGa()).isZero();
        assertThat(sumActiveGaHolds()).isZero();
        assertThat(activeSeatHolds(seatIds.get(0))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cancelled_events WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        // Event đã hủy: không tạo được giỏ mới dù product còn trả PUBLISHED (cache cũ/độ trễ)
        assertThatThrownBy(() -> createCart(UUID.randomUUID(), key(), ga(1)))
                .satisfies(e -> assertThat(new Outcome<>(null, e).errorCode()).isEqualTo(4002));
    }

    @Test
    void malformedCancelPayloadIsRejectedForRetry() {
        assertThatThrownBy(() -> orderEventListener.onEventCancelled("{not json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
