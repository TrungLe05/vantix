package com.dev.order_service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_kafka.event.PaymentSucceededEvent;
import com.dev.order_service.dto.response.CheckInWindowResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.service.OrderCommandService;
import com.dev.order_service.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D44: chỉ quét vé từ 3 giờ trước giờ bắt đầu tới khi sự kiện kết thúc (khung thật, không nới như test khác). */
@TestPropertySource(properties = "app.order.check-in-opens-before=PT3H")
class CheckInWindowIntegrationTest extends AbstractOrderIT {

    @Autowired
    private OrderCommandService orderCommandService;

    @Autowired
    private TicketService ticketService;

    @Test
    void scanningIsRejectedBeforeTheWindowOpensAndAcceptedInsideIt() {
        UUID buyer = UUID.randomUUID();
        String code = paidTicket(buyer);

        // Sự kiện còn 30 ngày: chưa mở quét, vé không bị đánh dấu đã dùng
        assertThatThrownBy(() -> ticketService.checkIn(UUID.randomUUID(), eventId, code))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getErrorCode().getCode()).isEqualTo(4021);
                    assertThat(((CheckInWindowResponse) e.getData()).opensAt())
                            .isEqualTo(eventStartAt.minus(3, ChronoUnit.HOURS));
                });
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE code = ?", String.class, code))
                .isEqualTo("VALID");

        // Sự kiện bắt đầu sau 2 giờ: đã trong khung
        eventStartAt = Instant.now().plus(2, ChronoUnit.HOURS);
        rebindCatalog();
        assertThat(ticketService.checkIn(UUID.randomUUID(), eventId, code).getTicketId()).isNotNull();
    }

    @Test
    void scanningIsRejectedAfterTheEventEnds() {
        UUID buyer = UUID.randomUUID();
        String code = paidTicket(buyer);
        // Bắt đầu 5 giờ trước, kết thúc (start + 3h) 2 giờ trước
        eventStartAt = Instant.now().minus(5, ChronoUnit.HOURS);
        rebindCatalog();
        assertThatThrownBy(() -> ticketService.checkIn(UUID.randomUUID(), eventId, code))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo(4021));
    }

    private String paidTicket(UUID buyer) {
        OrderResponse cart = createCart(buyer, key(), ga(1));
        OrderResponse pending = orderService.confirm(buyer, cart.getId(), key());
        orderCommandService.handlePaymentSucceeded(new PaymentSucceededEvent(UUID.randomUUID(), pending.getId(),
                buyer, pending.getTotalAmount(), pending.getCurrency(), Instant.now()));
        return orderService.getOrder(buyer, pending.getId()).getTickets().get(0).getCode();
    }

    /** Đổi giờ sự kiện, giữ nguyên id event/hạng vé. */
    private void rebindCatalog() {
        org.mockito.Mockito.when(productCatalogClient.getEvent(eventId)).thenReturn(
                new com.dev.order_service.dto.response.ProductCatalog(eventId, "PUBLISHED", eventStartAt,
                        eventStartAt.plus(3, ChronoUnit.HOURS), null, null, true, java.util.List.of(
                        new com.dev.order_service.dto.response.ProductCatalog.TicketType(seatedTypeId, "SEATED", "VIP",
                                SEAT_PRICE, seatIds.size()),
                        new com.dev.order_service.dto.response.ProductCatalog.TicketType(gaTypeId, "GENERAL_ADMISSION",
                                "GA", GA_PRICE, 10))));
    }
}
