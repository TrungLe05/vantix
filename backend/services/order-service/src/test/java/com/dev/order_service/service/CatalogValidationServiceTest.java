package com.dev.order_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.client.ProductCatalogClient;
import com.dev.order_service.dto.request.OrderItemRequest;
import com.dev.order_service.dto.response.ProductCatalog;
import com.dev.order_service.dto.response.ValidatedCatalog;
import com.dev.order_service.entity.OrderItem;
import com.dev.order_service.enums.OrderErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogValidationServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SEATED_ID = UUID.randomUUID();
    private static final UUID OTHER_SEATED_ID = UUID.randomUUID();
    private static final UUID GA_ID = UUID.randomUUID();
    private static final UUID SEAT_A1 = UUID.randomUUID();
    private static final UUID SEAT_INACTIVE = UUID.randomUUID();
    private static final UUID SEAT_OTHER_TYPE = UUID.randomUUID();

    private final ProductCatalogClient client = mock(ProductCatalogClient.class);
    private final CatalogValidationService service = new CatalogValidationService(client);

    @BeforeEach
    void stubCatalog() {
        stubEvent("PUBLISHED", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS));
        when(client.getSeatMap(EVENT_ID)).thenReturn(new ProductCatalog.SeatMap(UUID.randomUUID(), List.of(
                seat(SEAT_A1, "ACTIVE", SEATED_ID),
                seat(SEAT_INACTIVE, "INACTIVE", SEATED_ID),
                seat(SEAT_OTHER_TYPE, "ACTIVE", OTHER_SEATED_ID)
        )));
    }

    // ---------- loadCatalog ----------

    @Test
    void loadCatalogIndexesTicketTypesAndSeats() {
        ValidatedCatalog catalog = service.loadCatalog(EVENT_ID);

        assertThat(catalog.ticketTypes()).containsOnlyKeys(SEATED_ID, OTHER_SEATED_ID, GA_ID);
        assertThat(catalog.seats()).containsOnlyKeys(SEAT_A1, SEAT_INACTIVE, SEAT_OTHER_TYPE);
    }

    @Test
    void loadCatalogSkipsSeatMapWhenEventHasNone() {
        when(client.getEvent(EVENT_ID)).thenReturn(new ProductCatalog(EVENT_ID, "PUBLISHED", null, null, null,
                false, List.of(ga())));

        assertThat(service.loadCatalog(EVENT_ID).seats()).isEmpty();
        verify(client, never()).getSeatMap(EVENT_ID);
    }

    // ---------- cửa sổ bán ----------

    @Test
    void openSaleWindowPasses() {
        assertThatCode(() -> service.loadAndValidate(EVENT_ID, List.of(gaItem(2)))).doesNotThrowAnyException();
    }

    @Test
    void notPublishedEventIsNotAvailable() {
        stubEvent("DRAFT", null, null);
        assertError(() -> service.loadAndValidate(EVENT_ID, List.of(gaItem(1))), OrderErrorCode.EVENT_NOT_AVAILABLE);
        stubEvent("CANCELLED", null, null);
        assertError(() -> service.loadAndValidate(EVENT_ID, List.of(gaItem(1))), OrderErrorCode.EVENT_NOT_AVAILABLE);
    }

    @Test
    void salesNotStartedOrAlreadyEndedIsNotAvailable() {
        stubEvent("PUBLISHED", Instant.now().plus(1, ChronoUnit.HOURS), null);
        assertError(() -> service.loadAndValidate(EVENT_ID, List.of(gaItem(1))), OrderErrorCode.EVENT_NOT_AVAILABLE);

        stubEvent("PUBLISHED", null, Instant.now().minus(1, ChronoUnit.SECONDS));
        assertError(() -> service.loadAndValidate(EVENT_ID, List.of(gaItem(1))), OrderErrorCode.EVENT_NOT_AVAILABLE);
    }

    @Test
    void missingSalesBoundsMeanOpenEnded() {
        stubEvent("PUBLISHED", null, null);
        assertThatCode(() -> service.loadAndValidate(EVENT_ID, List.of(gaItem(1)))).doesNotThrowAnyException();
    }

    // ---------- item SEATED ----------

    @Test
    void validSeatPasses() {
        assertThatCode(() -> service.loadAndValidate(EVENT_ID, List.of(seatItem(SEATED_ID, SEAT_A1))))
                .doesNotThrowAnyException();
    }

    @Test
    void seatedItemNeedsExactlyOneSeatAndQuantityOne() {
        assertInvalid(new OrderItemRequest(SEATED_ID, null, 1));
        assertInvalid(new OrderItemRequest(SEATED_ID, SEAT_A1, 2));
    }

    @Test
    void sameSeatTwiceInOneRequestIsInvalid() {
        assertError(() -> service.loadAndValidate(EVENT_ID,
                        List.of(seatItem(SEATED_ID, SEAT_A1), seatItem(SEATED_ID, SEAT_A1))),
                OrderErrorCode.INVALID_ORDER_ITEMS);
    }

    @Test
    void unknownInactiveOrMismatchedSeatIsNotAvailable() {
        assertError(() -> service.loadAndValidate(EVENT_ID, List.of(seatItem(SEATED_ID, UUID.randomUUID()))),
                OrderErrorCode.TICKET_NOT_AVAILABLE);
        assertError(() -> service.loadAndValidate(EVENT_ID, List.of(seatItem(SEATED_ID, SEAT_INACTIVE))),
                OrderErrorCode.TICKET_NOT_AVAILABLE);
        // Ghế thuộc hạng vé khác với ticketTypeId gửi lên (không được mua ghế VIP với giá hạng thường)
        assertError(() -> service.loadAndValidate(EVENT_ID, List.of(seatItem(SEATED_ID, SEAT_OTHER_TYPE))),
                OrderErrorCode.TICKET_NOT_AVAILABLE);
    }

    // ---------- item GA ----------

    @Test
    void gaItemMustNotCarrySeatAndNeedsPositiveQuantity() {
        assertInvalid(new OrderItemRequest(GA_ID, SEAT_A1, 1));
        assertInvalid(new OrderItemRequest(GA_ID, null, 0));
        assertInvalid(new OrderItemRequest(GA_ID, null, null));
    }

    @Test
    void unknownTicketTypeIsInvalid() {
        assertInvalid(new OrderItemRequest(UUID.randomUUID(), null, 1));
    }

    @Test
    void ticketTypeWithoutPriceOrUnknownCategoryIsInvalid() {
        UUID noPrice = UUID.randomUUID();
        UUID weird = UUID.randomUUID();
        when(client.getEvent(EVENT_ID)).thenReturn(new ProductCatalog(EVENT_ID, "PUBLISHED", null, null, null, false,
                List.of(new ProductCatalog.TicketType(noPrice, "GENERAL_ADMISSION", "Free?", null, 5),
                        new ProductCatalog.TicketType(weird, "VIP_LOUNGE", "Lounge", BigDecimal.ONE, 5))));

        assertInvalid(new OrderItemRequest(noPrice, null, 1));
        assertInvalid(new OrderItemRequest(weird, null, 1));
    }

    // ---------- snapshot ----------

    @Test
    void toOrderItemSnapshotsNamePriceCategoryAndSeatLabel() {
        ValidatedCatalog catalog = service.loadCatalog(EVENT_ID);
        UUID orderId = UUID.randomUUID();

        OrderItem seated = service.toOrderItem(orderId, seatItem(SEATED_ID, SEAT_A1), catalog);
        assertThat(seated.getOrderId()).isEqualTo(orderId);
        assertThat(seated.getTicketNameSnapshot()).isEqualTo("VIP");
        assertThat(seated.getCategorySnapshot()).isEqualTo("SEATED");
        assertThat(seated.getUnitPriceSnapshot()).isEqualByComparingTo("150.00");
        assertThat(seated.getSubtotal()).isEqualByComparingTo("150.00");
        assertThat(seated.getSeatLabelSnapshot()).isEqualTo("Floor · A-01");

        OrderItem ga = service.toOrderItem(orderId, gaItem(3), catalog);
        assertThat(ga.getSubtotal()).isEqualByComparingTo("60.00");
        assertThat(ga.getSeatId()).isNull();
        assertThat(ga.getSeatLabelSnapshot()).isNull();
    }

    // ---------- helpers ----------

    private void stubEvent(String status, Instant salesStart, Instant salesEnd) {
        when(client.getEvent(EVENT_ID)).thenReturn(new ProductCatalog(EVENT_ID, status, null, salesStart, salesEnd, true,
                List.of(new ProductCatalog.TicketType(SEATED_ID, "SEATED", "VIP", new BigDecimal("150.00"), 2),
                        new ProductCatalog.TicketType(OTHER_SEATED_ID, "SEATED", "Standard", new BigDecimal("80.00"), 1),
                        ga())));
    }

    private static ProductCatalog.TicketType ga() {
        return new ProductCatalog.TicketType(GA_ID, "GENERAL_ADMISSION", "GA", new BigDecimal("20.00"), 100);
    }

    private static ProductCatalog.Seat seat(UUID id, String status, UUID ticketTypeId) {
        return new ProductCatalog.Seat(id, "Floor", "A", "01", "A-01", status,
                new ProductCatalog.TicketBrief(ticketTypeId, "x", BigDecimal.TEN));
    }

    private static OrderItemRequest seatItem(UUID ticketTypeId, UUID seatId) {
        return new OrderItemRequest(ticketTypeId, seatId, 1);
    }

    private static OrderItemRequest gaItem(int quantity) {
        return new OrderItemRequest(GA_ID, null, quantity);
    }

    private void assertInvalid(OrderItemRequest item) {
        assertError(() -> service.loadAndValidate(EVENT_ID, List.of(item)), OrderErrorCode.INVALID_ORDER_ITEMS);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, OrderErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
