package com.dev.order_service;

import com.dev.order_service.dto.request.AddOrderItemsRequest;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.PageResponse;
import com.dev.order_service.enums.OrderStatus;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OR02 phân trang/lọc status và giới hạn số vé mỗi Order (app.order.max-tickets-per-order = 10).
 */
class OrderListAndTicketLimitIntegrationTest extends AbstractOrderIT {

    // ---------- OR02 ----------

    @Test
    void defaultListReturnsOnlyActiveOrdersNewestFirst() {
        UUID buyer = UUID.randomUUID();
        OrderResponse expired = createCart(buyer, key(), ga(1));
        forceExpire(expired.getId());
        catalog(2, 10);
        OrderResponse older = createCart(buyer, key(), ga(1));
        catalog(2, 10);
        OrderResponse newer = createCart(buyer, key(), ga(1));

        PageResponse<OrderResponse> page = orderService.getOrders(buyer, null, 0, 20);

        assertThat(page.getContent()).extracting(OrderResponse::getId).containsExactly(newer.getId(), older.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void statusFilterIncludesHistoryAfterExpireOnRead() {
        UUID buyer = UUID.randomUUID();
        OrderResponse expired = createCart(buyer, key(), ga(1));
        forceExpire(expired.getId());

        // Order quá hạn được expire ngay khi đọc nên lọc EXPIRED thấy được ngay
        PageResponse<OrderResponse> page = orderService.getOrders(buyer, List.of("expired"), 0, 20);

        assertThat(page.getContent()).extracting(OrderResponse::getId).containsExactly(expired.getId());
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void paginationSplitsResults() {
        UUID buyer = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            catalog(1, 10);
            createCart(buyer, key(), ga(1));
        }

        PageResponse<OrderResponse> first = orderService.getOrders(buyer, null, 0, 2);
        PageResponse<OrderResponse> second = orderService.getOrders(buyer, null, 1, 2);

        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
    }

    @Test
    void listIsScopedToBuyerAndRejectsUnknownStatus() {
        createCart(UUID.randomUUID(), key(), ga(1));
        UUID stranger = UUID.randomUUID();

        assertThat(orderService.getOrders(stranger, null, 0, 20).getContent()).isEmpty();
        assertError(() -> orderService.getOrders(stranger, List.of("SHIPPED"), 0, 20), 1001);
    }

    // ---------- Giới hạn vé ----------

    @Test
    void singleRequestAboveLimitIsRejectedWithoutHolding() {
        catalog(2, 50);
        assertError(() -> createCart(UUID.randomUUID(), key(), ga(11)), 4012);
        assertError(() -> createCart(UUID.randomUUID(), key(), ga(10), seat(0)), 4012);
        assertThat(heldGa()).isZero();
        assertThat(activeSeatHolds(seatIds.get(0))).isZero();
    }

    @Test
    void limitCountsTicketsAlreadyInCart() {
        catalog(2, 50);
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), ga(8), seat(0));

        assertError(() -> orderService.addItems(buyer, cart.getId(),
                new AddOrderItemsRequest(List.of(ga(2))), key()), 4012);
        // Append qua OR01 cho cùng event cũng bị tính vào giới hạn
        assertError(() -> createCart(buyer, key(), seat(1), ga(1)), 4012);
        OrderResponse filled = orderService.addItems(buyer, cart.getId(),
                new AddOrderItemsRequest(List.of(ga(1))), key());

        assertThat(filled.getItems().stream().mapToInt(i -> i.getQuantity()).sum()).isEqualTo(10);
        assertThat(heldGa()).isEqualTo(9);
    }

    @Test
    void quantityIncreaseRespectsLimitButDecreaseAlwaysAllowed() {
        catalog(1, 50);
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), seat(0), ga(5));
        UUID gaItem = cart.getItems().stream().filter(i -> "GENERAL_ADMISSION".equals(i.getCategory()))
                .findFirst().orElseThrow().getId();

        assertError(() -> orderService.updateItemQuantity(buyer, cart.getId(), gaItem, 10), 4012);
        assertThat(orderService.updateItemQuantity(buyer, cart.getId(), gaItem, 9).getTotalAmount())
                .isEqualByComparingTo("330.00");
        assertThat(orderService.updateItemQuantity(buyer, cart.getId(), gaItem, 2).getTotalAmount())
                .isEqualByComparingTo("190.00");
        assertThat(heldGa()).isEqualTo(2);
    }

    @Test
    void removedItemsFreeUpLimit() {
        catalog(1, 50);
        UUID buyer = UUID.randomUUID();
        OrderResponse cart = createCart(buyer, key(), seat(0), ga(9));
        UUID seatItem = cart.getItems().stream().filter(i -> "SEATED".equals(i.getCategory()))
                .findFirst().orElseThrow().getId();
        orderService.removeItem(buyer, cart.getId(), seatItem);

        OrderResponse after = orderService.addItems(buyer, cart.getId(),
                new AddOrderItemsRequest(List.of(ga(1))), key());

        assertThat(after.getStatus()).isEqualTo(OrderStatus.CART);
        assertThat(heldGa()).isEqualTo(10);
    }

    private static void assertError(ThrowableAssert.ThrowingCallable call, int code) {
        assertThatThrownBy(call).satisfies(e -> assertThat(new Outcome<>(null, e).errorCode()).isEqualTo(code));
    }
}
