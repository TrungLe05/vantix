package com.dev.order_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_api_response.exception.CommonErrorCode;
import com.dev.order_service.client.PaymentClient;
import com.dev.order_service.client.PaymentFeignClient;
import com.dev.order_service.dto.request.AddOrderItemsRequest;
import com.dev.order_service.dto.request.CreateOrderRequest;
import com.dev.order_service.dto.request.OrderItemRequest;
import com.dev.order_service.dto.response.AvailabilityResponse;
import com.dev.order_service.dto.response.CheckoutResponse;
import com.dev.order_service.dto.response.RefundQuoteResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.PageResponse;
import com.dev.order_service.dto.response.ValidatedCatalog;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.enums.TicketStatus;
import com.dev.order_service.enums.OrderErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final CatalogValidationService catalogValidationService;
    private final OrderCommandService orderCommandService;
    private final OrderRequestHasher requestHasher;
    private final PaymentClient paymentClient;

    // Stripe không nhận khoản USD dưới 0.50; đơn 0 đồng (vé miễn phí) chưa hỗ trợ ở P4
    private static final BigDecimal MIN_PAYABLE_AMOUNT = new BigDecimal("0.50");

    @Override
    public OrderResponse create(UUID buyerId, CreateOrderRequest request, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        orderCommandService.requireWithinTicketLimit(request.getItems());
        String requestHash = requestHasher.hash(request.getEventId(), null, request.getItems());
        var replay = orderCommandService.replay(buyerId, "CREATE_CART", idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ValidatedCatalog catalog = catalogValidationService.loadAndValidate(request.getEventId(), request.getItems());
        try {
            return orderCommandService.createOrAppend(
                    buyerId, request.getEventId(), request.getItems(), catalog, idempotencyKey, requestHash
            );
        } catch (DataIntegrityViolationException firstConflict) {
            var racedReplay = orderCommandService.replay(buyerId, "CREATE_CART", idempotencyKey, requestHash);
            if (racedReplay.isPresent()) {
                return racedReplay.get();
            }
            try {
                return orderCommandService.createOrAppend(
                        buyerId, request.getEventId(), request.getItems(), catalog, idempotencyKey, requestHash
                );
            } catch (DataIntegrityViolationException conflict) {
                throw new AppException(OrderErrorCode.CART_ITEMS_UNAVAILABLE);
            }
        }
    }

    @Override
    public PageResponse<OrderResponse> getOrders(UUID buyerId, List<String> statuses, int page, int size) {
        return orderCommandService.getOrders(buyerId, parseStatuses(statuses), page, size);
    }

    // Không truyền status → giữ hành vi cũ của OR02: chỉ Order đang hoạt động (CART, PENDING_PAYMENT)
    private List<OrderStatus> parseStatuses(List<String> raw) {
        if (raw == null || raw.stream().allMatch(s -> s == null || s.isBlank())) {
            return List.of(OrderStatus.CART, OrderStatus.PENDING_PAYMENT);
        }
        try {
            return raw.stream()
                    .filter(StringUtils::hasText)
                    .map(s -> OrderStatus.valueOf(s.trim().toUpperCase(Locale.ROOT)))
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException ex) {
            throw new AppException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    @Override
    public OrderResponse getOrder(UUID buyerId, UUID orderId) {
        return orderCommandService.getOwned(buyerId, orderId);
    }

    @Override
    public OrderResponse addItems(
            UUID buyerId, UUID orderId, AddOrderItemsRequest request, String idempotencyKey
    ) {
        requireIdempotencyKey(idempotencyKey);
        orderCommandService.requireWithinTicketLimit(request.getItems());
        String operation = "ADD_ITEMS:" + orderId;
        String requestHash = requestHasher.hash(null, orderId, request.getItems());
        var replay = orderCommandService.replay(buyerId, operation, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        OrderResponse current = orderCommandService.getOwned(buyerId, orderId);
        if (current.getStatus() != OrderStatus.CART) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        ValidatedCatalog catalog = catalogValidationService.loadAndValidate(current.getEventId(), request.getItems());
        try {
            return orderCommandService.addItems(
                    buyerId, orderId, request.getItems(), catalog, idempotencyKey, requestHash
            );
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(OrderErrorCode.CART_ITEMS_UNAVAILABLE);
        }
    }

    @Override
    public OrderResponse removeItem(UUID buyerId, UUID orderId, UUID itemId) {
        return orderCommandService.removeItem(buyerId, orderId, itemId);
    }

    @Override
    public OrderResponse updateItemQuantity(UUID buyerId, UUID orderId, UUID itemId, int quantity) {
        if (quantity < 1) {
            throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
        }
        OrderResponse current = orderCommandService.getOwned(buyerId, orderId);
        var item = current.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new AppException(OrderErrorCode.INVALID_ORDER_ITEMS));
        if (!"GENERAL_ADMISSION".equals(item.getCategory())) {
            throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
        }
        if (current.getStatus() != OrderStatus.CART) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        if (quantity == item.getQuantity()) {
            return current;
        }

        ValidatedCatalog catalog = null;
        if (quantity > item.getQuantity()) {
            catalog = catalogValidationService.loadAndValidate(current.getEventId(), List.of(
                    new OrderItemRequest(item.getTicketTypeId(), null, quantity - item.getQuantity())
            ));
        }
        try {
            return orderCommandService.updateItemQuantity(
                    buyerId, orderId, itemId, quantity, catalog
            );
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(OrderErrorCode.CART_ITEMS_UNAVAILABLE);
        }
    }

    @Override
    public OrderResponse cancel(UUID buyerId, UUID orderId) {
        return orderCommandService.cancel(buyerId, orderId);
    }

    @Override
    public OrderResponse confirm(UUID buyerId, UUID orderId, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        String operation = "CONFIRM:" + orderId;
        String requestHash = requestHasher.hash(null, orderId, List.of());
        var replay = orderCommandService.replay(buyerId, operation, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        OrderResponse current = orderCommandService.getOwned(buyerId, orderId);
        if (current.getStatus() == OrderStatus.EXPIRED) {
            throw new AppException(OrderErrorCode.HOLD_EXPIRED);
        }
        ValidatedCatalog catalog = catalogValidationService.loadCatalog(current.getEventId());
        catalogValidationService.validateSaleWindow(catalog.event());
        OrderResponse confirmed = orderCommandService.confirm(buyerId, orderId, idempotencyKey, requestHash);
        if (confirmed.getStatus() == OrderStatus.EXPIRED) {
            throw new AppException(OrderErrorCode.HOLD_EXPIRED);
        }
        return confirmed;
    }

    @Override
    public AvailabilityResponse getAvailability(UUID eventId) {
        ValidatedCatalog catalog = catalogValidationService.loadCatalog(eventId);
        if ("CANCELLED".equals(catalog.event().status())) {
            orderCommandService.cancelUnpaidOrdersForEvent(eventId);
        }
        return orderCommandService.getAvailability(eventId, catalog);
    }

    /**
     * OR09 — lấy link thanh toán cho Order PENDING_PAYMENT. Không ghi gì ở DB Order và không cần
     * Idempotency-Key: Payment idempotent theo orderId (một Order chỉ có một phiên thanh toán),
     * gọi lại nhận đúng link cũ.
     */
    @Override
    public CheckoutResponse checkout(UUID buyerId, UUID orderId) {
        // getOwned expire Order nếu đã quá deadline hold
        OrderResponse current = orderCommandService.getOwned(buyerId, orderId);
        if (current.getStatus() == OrderStatus.EXPIRED) {
            throw new AppException(OrderErrorCode.HOLD_EXPIRED);
        }
        if (current.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        if (current.getTotalAmount().compareTo(MIN_PAYABLE_AMOUNT) < 0) {
            throw new AppException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        // Deadline thanh toán = deadline hold: quá mốc này Payment tự expire phiên Stripe
        PaymentFeignClient.CheckoutSession session = paymentClient.createCheckoutSession(
                orderId, buyerId, current.getTotalAmount(), current.getCurrency(), current.getHoldExpiresAt()
        );
        return CheckoutResponse.builder()
                .orderId(orderId)
                .checkoutUrl(session.checkoutUrl())
                .expiresAt(current.getHoldExpiresAt())
                .build();
    }

    @Override
    public RefundQuoteResponse getRefundQuote(UUID buyerId, UUID orderId) {
        OrderResponse current = orderCommandService.getOwned(buyerId, orderId);
        Instant eventStartAt = null;
        int percent = 0;
        if (isPaid(current)) {
            eventStartAt = catalogValidationService.eventStartAt(current.getEventId());
            percent = RefundPolicy.percent(Instant.now(), eventStartAt);
            // Đơn đã có vé check-in thì OR11 từ chối, quote phải nói trước điều đó
            if (current.getTickets().stream().anyMatch(t -> t.getStatus() == TicketStatus.CHECKED_IN)) {
                percent = 0;
            }
        }
        return RefundQuoteResponse.builder()
                .orderId(orderId)
                .refundable(percent > 0)
                .percent(percent)
                .amount(RefundPolicy.amount(current.getTotalAmount(), percent))
                .currency(current.getCurrency())
                .paidAmount(current.getTotalAmount())
                .eventStartAt(eventStartAt)
                .build();
    }

    @Override
    public OrderResponse requestRefund(UUID buyerId, UUID orderId) {
        OrderResponse current = orderCommandService.getOwned(buyerId, orderId);
        // Bấm lại sau khi đã yêu cầu: trả trạng thái hiện tại, không báo lỗi
        if (current.getStatus() == OrderStatus.REFUND_PENDING || current.getStatus() == OrderStatus.REFUNDED) {
            return current;
        }
        if (!isPaid(current)) {
            throw new AppException(OrderErrorCode.REFUND_NOT_ALLOWED);
        }
        // % tính lại ở server tại thời điểm yêu cầu, không tin số từ client/quote cũ
        int percent = RefundPolicy.percent(
                Instant.now(), catalogValidationService.eventStartAt(current.getEventId()));
        if (percent <= 0) {
            throw new AppException(OrderErrorCode.REFUND_NOT_ALLOWED);
        }
        return orderCommandService.requestRefund(buyerId, orderId, percent);
    }

    private boolean isPaid(OrderResponse order) {
        return order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.TICKETS_ISSUED;
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 128) {
            throw new AppException(OrderErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
    }
}
