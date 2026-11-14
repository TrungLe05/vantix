package com.dev.order_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_kafka.event.PaymentFailedEvent;
import com.dev.commonlib_kafka.event.PaymentSucceededEvent;
import com.dev.commonlib_kafka.event.RefundCompletedEvent;
import com.dev.commonlib_kafka.event.RefundFailedEvent;
import com.dev.commonlib_kafka.event.OrderRefundedEvent;
import com.dev.commonlib_kafka.event.RefundRequestedEvent;
import com.dev.commonlib_kafka.event.TicketsIssuedEvent;
import com.dev.order_service.entity.OrderRefund;
import com.dev.order_service.enums.RefundReason;
import com.dev.order_service.enums.RefundStatus;
import com.dev.order_service.repository.OrderRefundRepository;
import com.dev.order_service.dto.request.OrderItemRequest;
import com.dev.order_service.dto.response.AvailabilityResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.PageResponse;
import com.dev.order_service.dto.response.ProductCatalog;
import com.dev.order_service.dto.response.ValidatedCatalog;
import com.dev.order_service.entity.Hold;
import com.dev.order_service.enums.HoldStatus;
import com.dev.order_service.entity.IdempotencyRecord;
import com.dev.order_service.entity.Order;
import com.dev.order_service.entity.OrderItem;
import com.dev.order_service.enums.OrderItemStatus;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.entity.TicketInventory;
import com.dev.order_service.event.CartItemAddedEvent;
import com.dev.order_service.event.OrderEventPublisher;
import com.dev.order_service.enums.OrderErrorCode;
import com.dev.order_service.repository.HoldRepository;
import com.dev.order_service.repository.IdempotencyRecordRepository;
import com.dev.order_service.repository.OrderItemRepository;
import com.dev.order_service.repository.OrderRepository;
import com.dev.order_service.repository.TicketInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderCommandService {

    private static final List<HoldStatus> ACTIVE_HOLD_STATUSES = List.of(HoldStatus.HELD, HoldStatus.CONFIRMED);
    private static final List<OrderStatus> UNPAID_ORDER_STATUSES =
            List.of(OrderStatus.CART, OrderStatus.PENDING_PAYMENT);
    private static final List<OrderStatus> PAID_ORDER_STATUSES =
            List.of(OrderStatus.PAID, OrderStatus.TICKETS_ISSUED);

    private final OrderRefundRepository orderRefundRepository;
    private final TicketService ticketService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final HoldRepository holdRepository;
    private final TicketInventoryRepository ticketInventoryRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final OrderMapper orderMapper;
    private final CatalogValidationService catalogValidationService;
    private final OrderEventPublisher orderEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.order.hold-duration:PT10M}")
    private Duration holdDuration;

    // Tổng số vé (ghế + GA) tối đa của một Order — xem D24 về giá trị mặc định
    @Value("${app.order.max-tickets-per-order:10}")
    private int maxTicketsPerOrder;

    /** Chặn sớm request tự nó đã vượt giới hạn, trước khi gọi Product hay chạm DB. */
    public void requireWithinTicketLimit(List<OrderItemRequest> requests) {
        long requested = requests.stream()
                .mapToLong(r -> r.getQuantity() == null ? 0 : r.getQuantity())
                .sum();
        if (requested > maxTicketsPerOrder) {
            throw new AppException(OrderErrorCode.TICKET_LIMIT_EXCEEDED);
        }
    }

    // Gọi khi đã khóa Order: tổng vé ACTIVE hiện có + phần thêm không được vượt giới hạn
    private void requireCapacityLeft(Order order, int additionalTickets) {
        int current = orderItemRepository
                .findByOrderIdAndItemStatusOrderByCreatedAtAsc(order.getId(), OrderItemStatus.ACTIVE)
                .stream().mapToInt(OrderItem::getQuantity).sum();
        if (current + additionalTickets > maxTicketsPerOrder) {
            throw new AppException(OrderErrorCode.TICKET_LIMIT_EXCEEDED);
        }
    }



    @Transactional(readOnly = true)
    public Optional<OrderResponse> replay(UUID buyerId, String operation, String key, String requestHash) {
        return idempotencyRecordRepository.findByBuyerIdAndOperationAndIdempotencyKey(buyerId, operation, key)
                .map(record -> replayResponse(record, requestHash));
    }

    @Transactional
    public OrderResponse createOrAppend(
            UUID buyerId, UUID eventId, List<OrderItemRequest> requests, ValidatedCatalog catalog,
            String idempotencyKey, String requestHash
    ) {
        Optional<OrderResponse> replay = replay(buyerId, "CREATE_CART", idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!eventId.equals(catalog.event().id())) {
            throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
        }
        lockEvent(eventId);
        ensureEventNotCancelled(eventId);

        Instant now = Instant.now();
        Order order = orderRepository.findCartForUpdate(buyerId, eventId, OrderStatus.CART).orElse(null);
        if (order != null && isExpired(order, now)) {
            expireOrder(order, now);
            orderRepository.flush();
            order = null;
        }
        if (order == null) {
            order = orderRepository.saveAndFlush(Order.builder()
                    .buyerId(buyerId)
                    .eventId(eventId)
                    .status(OrderStatus.CART)
                    .currency("USD")
                    .totalAmount(BigDecimal.ZERO)
                    .holdExpiresAt(now.plus(holdDuration))
                    .build());
        }

        appendItems(order, requests, catalog, now);
        orderRepository.saveAndFlush(order);
        persistIdempotency(buyerId, "CREATE_CART", idempotencyKey, requestHash, order.getId());
        publishAfterCommit(order, buyerId, eventId, requests);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse addItems(
            UUID buyerId, UUID orderId, List<OrderItemRequest> requests, ValidatedCatalog catalog,
            String idempotencyKey, String requestHash
    ) {
        String operation = "ADD_ITEMS:" + orderId;
        Optional<OrderResponse> replay = replay(buyerId, operation, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        Order summary = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        lockEvent(summary.getEventId());
        ensureEventNotCancelled(summary.getEventId());
        Order order = orderRepository.findOwnedForUpdate(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        Instant now = Instant.now();
        if (order.getStatus() == OrderStatus.CART && isExpired(order, now)) {
            expireOrder(order, now);
            orderRepository.flush();
            return orderMapper.toResponse(order);
        }
        if (order.getStatus() != OrderStatus.CART || !order.getEventId().equals(catalog.event().id())) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }

        appendItems(order, requests, catalog, now);
        orderRepository.saveAndFlush(order);
        persistIdempotency(buyerId, operation, idempotencyKey, requestHash, order.getId());
        publishAfterCommit(order, buyerId, order.getEventId(), requests);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse removeItem(UUID buyerId, UUID orderId, UUID itemId) {
        Order order = orderRepository.findOwnedForUpdate(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        Instant now = Instant.now();
        if (order.getStatus() == OrderStatus.CART && isExpired(order, now)) {
            expireOrder(order, now);
            return orderMapper.toResponse(order);
        }
        if (order.getStatus() != OrderStatus.CART) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }

        OrderItem item = orderItemRepository.findByIdAndOrderId(itemId, orderId)
                .orElseThrow(() -> new AppException(OrderErrorCode.INVALID_ORDER_ITEMS));
        if (item.getItemStatus() != OrderItemStatus.ACTIVE) {
            return orderMapper.toResponse(order);
        }

        holdRepository.findByOrderItemId(itemId).ifPresent(hold -> {
            if (ACTIVE_HOLD_STATUSES.contains(hold.getStatus())) {
                releaseInventory(hold);
                hold.setStatus(HoldStatus.RELEASED);
                deleteRedisAfterCommit(redisKey(hold));
            }
        });
        item.setItemStatus(OrderItemStatus.REMOVED);
        order.setTotalAmount(order.getTotalAmount().subtract(item.getSubtotal()).max(BigDecimal.ZERO));

        boolean hasOtherActiveItems = orderItemRepository
                .findByOrderIdAndItemStatusOrderByCreatedAtAsc(orderId, OrderItemStatus.ACTIVE)
                .stream().anyMatch(existing -> !existing.getId().equals(itemId));
        if (!hasOtherActiveItems) {
            order.setStatus(OrderStatus.CANCELLED);
        }
        orderRepository.saveAndFlush(order);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse updateItemQuantity(
            UUID buyerId, UUID orderId, UUID itemId, int targetQuantity, ValidatedCatalog catalog
    ) {
        Order summary = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        lockEvent(summary.getEventId());
        ensureEventNotCancelled(summary.getEventId());
        Order order = orderRepository.findOwnedForUpdate(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        Instant now = Instant.now();
        if (order.getStatus() == OrderStatus.CART && isExpired(order, now)) {
            expireOrder(order, now);
            orderRepository.flush();
            return orderMapper.toResponse(order);
        }
        if (order.getStatus() != OrderStatus.CART) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }

        OrderItem item = orderItemRepository.findByIdAndOrderId(itemId, orderId)
                .orElseThrow(() -> new AppException(OrderErrorCode.INVALID_ORDER_ITEMS));
        if (item.getItemStatus() != OrderItemStatus.ACTIVE
                || item.getSeatId() != null
                || !"GENERAL_ADMISSION".equals(item.getCategorySnapshot())) {
            throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
        }
        Hold hold = holdRepository.findByOrderItemId(itemId)
                .orElseThrow(() -> new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY));
        if (hold.getStatus() != HoldStatus.HELD || !hold.getExpiresAt().isAfter(now)) {
            throw new AppException(OrderErrorCode.HOLD_EXPIRED);
        }
        int delta = targetQuantity - item.getQuantity();
        if (delta > 0) {
            requireCapacityLeft(order, delta);
            ProductCatalog.TicketType type = catalog == null ? null : catalog.ticketTypes().get(item.getTicketTypeId());
            if (type == null || !"GENERAL_ADMISSION".equals(type.category())) {
                throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
            }
            ensureInventory(type, order.getEventId());
            if (ticketInventoryRepository.tryHold(type.id(), delta, now) != 1) {
                throw new AppException(OrderErrorCode.CART_ITEMS_UNAVAILABLE);
            }
        } else {
            releaseInventoryQuantity(hold, -delta);
        }

        int oldQuantity = item.getQuantity();
        BigDecimal oldSubtotal = item.getSubtotal();
        BigDecimal newSubtotal = item.getUnitPriceSnapshot().multiply(BigDecimal.valueOf(targetQuantity));
        item.setQuantity(targetQuantity);
        item.setSubtotal(newSubtotal);
        hold.setQuantity(targetQuantity);
        order.setTotalAmount(order.getTotalAmount().subtract(oldSubtotal).add(newSubtotal));
        orderRepository.saveAndFlush(order);

        if (delta > 0) {
            publishAfterCommit(order, buyerId, order.getEventId(), List.of(
                    new OrderItemRequest(item.getTicketTypeId(), null, delta)
            ));
        }
        log.debug("GA cart quantity updated: orderId={}, itemId={}, oldQuantity={}, newQuantity={}",
                orderId, itemId, oldQuantity, targetQuantity);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse cancel(UUID buyerId, UUID orderId) {
        Order order = orderRepository.findOwnedForUpdate(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.EXPIRED) {
            return orderMapper.toResponse(order);
        }
        // Chỉ hủy được giỏ CART (D26/D27): ở PENDING_PAYMENT buyer có thể đang trả tiền trên Stripe,
        // hủy lúc này dễ dẫn tới thu tiền cho đơn đã hủy. Đơn chờ thanh toán tự hết hạn theo deadline hold.
        if (order.getStatus() != OrderStatus.CART) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        cancelOrder(order);
        return orderMapper.toResponse(order);
    }

    // ---------- P4: kết quả thanh toán và hoàn tiền ----------

    /**
     * payment.succeeded: PENDING_PAYMENT → PAID, hold → PURCHASED, tồn GA chuyển held → sold.
     * Idempotent: Order đã PAID/đang hoàn thì bỏ qua. Order đã EXPIRED/CANCELLED (vé đã nhả cho
     * người khác) thì không "hồi sinh" mà yêu cầu hoàn 100% — tiền vào trễ không được làm bán trùng.
     */
    @Transactional
    public void handlePaymentSucceeded(PaymentSucceededEvent event) {
        Order order = orderRepository.findByIdForUpdate(event.orderId()).orElse(null);
        if (order == null) {
            log.error("payment.succeeded cho Order không tồn tại, orderId={} paymentId={}",
                    event.orderId(), event.paymentId());
            return;
        }
        switch (order.getStatus()) {
            case PENDING_PAYMENT -> markPaid(order, event);
            case EXPIRED, CANCELLED -> {
                log.warn("LATE_PAYMENT orderId={} status={} paymentId={}; yêu cầu hoàn 100%",
                        order.getId(), order.getStatus(), event.paymentId());
                createRefund(order, RefundReason.LATE_PAYMENT, 100, event.amount());
            }
            case CART -> log.error("payment.succeeded cho Order còn CART, orderId={} paymentId={}",
                    order.getId(), event.paymentId());
            default -> log.debug("payment.succeeded lặp, orderId={} status={}", order.getId(), order.getStatus());
        }
    }

    private void markPaid(Order order, PaymentSucceededEvent event) {
        // Còn PENDING_PAYMENT nghĩa là hold chưa bị nhả (job expire chưa chạm tới), kể cả khi vừa quá
        // deadline vài trăm ms: vé vẫn đang giữ cho buyer này nên chấp nhận thanh toán.
        List<Hold> holds = holdRepository.findActiveByOrderIdForUpdate(order.getId(), ACTIVE_HOLD_STATUSES);
        if (holds.isEmpty()) {
            throw new IllegalStateException("Order PENDING_PAYMENT không còn hold hiệu lực: " + order.getId());
        }
        Instant now = Instant.now();
        for (Hold hold : holds) {
            if (hold.getSeatId() == null
                    && ticketInventoryRepository.confirmSale(hold.getTicketTypeId(), hold.getQuantity(), now) != 1) {
                throw new IllegalStateException("Không chuyển được tồn GA held → sold, holdId=" + hold.getId());
            }
            hold.setStatus(HoldStatus.PURCHASED);
        }
        if (event.amount() == null || event.amount().compareTo(order.getTotalAmount()) != 0) {
            // Order đóng băng ở PENDING_PAYMENT nên không thể lệch; nếu lệch là lỗi tích hợp cần xem tay
            log.error("PAYMENT_AMOUNT_MISMATCH orderId={} paymentId={}", order.getId(), event.paymentId());
        }
        Instant paidAt = event.paidAt() != null ? event.paidAt() : now;
        order.setPaidAt(paidAt);
        // P5: phát hành vé ngay trong transaction ghi nhận thanh toán → Order đi thẳng PAID → TICKETS_ISSUED.
        // Cùng transaction nên không có Order "đã trả tiền mà không có vé" (D31).
        int ticketCount = ticketService.issueTickets(order, holds);
        order.setStatus(OrderStatus.TICKETS_ISSUED);
        orderRepository.save(order);
        orderEventPublisher.recordTicketsIssued(new TicketsIssuedEvent(
                order.getId(), order.getBuyerId(), order.getEventId(), ticketCount,
                order.getTotalAmount(), order.getCurrency(), now
        ));
        log.info("Order đã thanh toán, phát hành {} vé, orderId={} paymentId={}",
                ticketCount, order.getId(), event.paymentId());
    }

    /** payment.failed: nhả hold nếu Order còn chờ thanh toán; các trạng thái khác đã được xử lý. */
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        orderRepository.findByIdForUpdate(event.orderId()).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                return;
            }
            if (PaymentFailedEvent.REASON_EXPIRED.equals(event.reason())) {
                expireOrder(order, Instant.now());
            } else {
                cancelOrder(order);
            }
        });
    }

    /**
     * Buyer hủy đơn đã thanh toán. {@code percent} do caller tính theo {@link RefundPolicy} từ giờ
     * bắt đầu sự kiện (gọi Product ngoài transaction). Gọi lại khi đã REFUND_PENDING/REFUNDED trả
     * nguyên trạng thái hiện tại.
     */
    @Transactional
    public OrderResponse requestRefund(UUID buyerId, UUID orderId, int percent) {
        Order order = orderRepository.findOwnedForUpdate(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() == OrderStatus.REFUND_PENDING || order.getStatus() == OrderStatus.REFUNDED) {
            return orderMapper.toResponse(order);
        }
        if (!PAID_ORDER_STATUSES.contains(order.getStatus()) || percent <= 0) {
            throw new AppException(OrderErrorCode.REFUND_NOT_ALLOWED);
        }
        // Đã có người vào cổng bằng vé của đơn: không hoàn (hoàn cả đơn, D29)
        if (ticketService.hasCheckedInTickets(order.getId())) {
            throw new AppException(OrderErrorCode.REFUND_NOT_ALLOWED);
        }
        refundPaidOrder(order, RefundReason.BUYER_REQUEST, percent);
        orderRepository.flush();
        return orderMapper.toResponse(order);
    }

    @Transactional
    public void handleRefundCompleted(RefundCompletedEvent event) {
        // Khóa Order trước rồi mới đọc refund (cùng thứ tự với mọi đường ghi khác)
        orderRepository.findByIdForUpdate(event.orderId()).ifPresent(order ->
                orderRefundRepository.findById(event.refundId()).ifPresent(refund -> {
                    if (refund.getStatus() == RefundStatus.COMPLETED) {
                        return;
                    }
                    refund.setStatus(RefundStatus.COMPLETED);
                    refund.setFailureReason(null);
                    refund.setCompletedAt(event.completedAt() != null ? event.completedAt() : Instant.now());
                    // P5: Notification không đọc refund.completed (không có buyer/lý do) mà nghe Order
                    orderEventPublisher.recordOrderRefunded(new OrderRefundedEvent(
                            refund.getId(), order.getId(), order.getBuyerId(), order.getEventId(),
                            refund.getAmount(), refund.getCurrency(), refund.getReason().name(),
                            refund.getCompletedAt()
                    ));
                    // Thanh toán trễ: Order giữ EXPIRED/CANCELLED, chỉ refund đổi trạng thái
                    if (order.getStatus() == OrderStatus.REFUND_PENDING) {
                        order.setStatus(OrderStatus.REFUNDED);
                        orderRepository.save(order);
                    }
                }));
    }

    @Transactional
    public void handleRefundFailed(RefundFailedEvent event) {
        orderRepository.findByIdForUpdate(event.orderId()).ifPresent(order ->
                orderRefundRepository.findById(event.refundId()).ifPresent(refund -> {
                    if (refund.getStatus() != RefundStatus.REQUESTED) {
                        return;
                    }
                    refund.setStatus(RefundStatus.FAILED);
                    refund.setFailureReason(event.reason() == null || event.reason().length() <= 100
                            ? event.reason() : event.reason().substring(0, 100));
                    // Order giữ REFUND_PENDING: tiền vẫn nợ buyer, cần xử lý tay
                    log.error("REFUND_FAILED orderId={} refundId={} reason={}",
                            order.getId(), refund.getId(), refund.getFailureReason());
                }));
    }

    // Gọi khi đã khóa Order đang PAID: trả vé về kho ngay (buyer đã từ bỏ vé / event đã hủy) rồi
    // ghi yêu cầu hoàn; việc chuyển tiền do Payment làm bất đồng bộ.
    private void refundPaidOrder(Order order, RefundReason reason, int percent) {
        Instant now = Instant.now();
        for (Hold hold : holdRepository.findActiveByOrderIdForUpdate(order.getId(), List.of(HoldStatus.PURCHASED))) {
            if (hold.getSeatId() == null
                    && ticketInventoryRepository.releaseSold(hold.getTicketTypeId(), hold.getQuantity(), now) != 1) {
                log.error("Could not release sold GA inventory: holdId={}, ticketTypeId={}, quantity={}",
                        hold.getId(), hold.getTicketTypeId(), hold.getQuantity());
                throw new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY);
            }
            hold.setStatus(HoldStatus.RELEASED);
            orderItemRepository.findByIdAndOrderId(hold.getOrderItemId(), order.getId())
                    .ifPresent(item -> item.setItemStatus(OrderItemStatus.CANCELLED));
            deleteRedisAfterCommit(redisKey(hold));
        }
        ticketService.voidValidTickets(order.getId());
        order.setStatus(OrderStatus.REFUND_PENDING);
        orderRepository.save(order);
        createRefund(order, reason, percent, RefundPolicy.amount(order.getTotalAmount(), percent));
    }

    // Idempotent theo Order: uq_order_refunds_order_id là lớp chặn cuối, check ở đây để không ném lỗi
    // khi consumer nhận lại bản tin
    private void createRefund(Order order, RefundReason reason, int percent, BigDecimal amount) {
        if (orderRefundRepository.findByOrderId(order.getId()).isPresent()) {
            return;
        }
        if (amount == null || amount.signum() <= 0) {
            log.error("Bỏ qua refund số tiền không dương, orderId={} reason={}", order.getId(), reason);
            return;
        }
        OrderRefund refund = orderRefundRepository.saveAndFlush(OrderRefund.builder()
                .orderId(order.getId())
                .buyerId(order.getBuyerId())
                .reason(reason)
                .refundPercent(percent)
                .amount(amount)
                .currency(order.getCurrency())
                .status(RefundStatus.REQUESTED)
                .build());
        orderEventPublisher.recordRefundRequested(new RefundRequestedEvent(
                refund.getId(), order.getId(), order.getBuyerId(), amount, order.getCurrency(),
                reason.name(), Instant.now()
        ), order.getEventId());
    }

    @Transactional
    public OrderResponse confirm(UUID buyerId, UUID orderId, String idempotencyKey, String requestHash) {
        String operation = "CONFIRM:" + orderId;
        Optional<OrderResponse> replay = replay(buyerId, operation, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        Order summary = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        lockEvent(summary.getEventId());
        ensureEventNotCancelled(summary.getEventId());
        Order order = orderRepository.findOwnedForUpdate(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        Instant now = Instant.now();
        if (order.getStatus() == OrderStatus.CART && isExpired(order, now)) {
            expireOrder(order, now);
            orderRepository.flush();
            return orderMapper.toResponse(order);
        }
        if (order.getStatus() != OrderStatus.CART) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }

        List<Hold> holds = holdRepository.findActiveByOrderIdForUpdate(orderId, ACTIVE_HOLD_STATUSES);
        if (holds.isEmpty() || holds.stream().anyMatch(hold -> !hold.getExpiresAt().isAfter(now))) {
            expireOrder(order, now);
            orderRepository.flush();
            return orderMapper.toResponse(order);
        }
        holds.stream().filter(hold -> hold.getStatus() == HoldStatus.HELD)
                .forEach(hold -> hold.setStatus(HoldStatus.CONFIRMED));
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        orderRepository.saveAndFlush(order);
        persistIdempotency(buyerId, operation, idempotencyKey, requestHash, orderId);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse getOwned(UUID buyerId, UUID orderId) {
        Order order = orderRepository.findOwnedForUpdate(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        if (UNPAID_ORDER_STATUSES.contains(order.getStatus()) && isExpired(order, Instant.now())) {
            expireOrder(order, Instant.now());
        }
        return orderMapper.toResponse(order);
    }

    @Transactional
    public PageResponse<OrderResponse> getOrders(UUID buyerId, List<OrderStatus> statuses, int page, int size) {
        Instant now = Instant.now();
        // Expire trước khi nạp danh sách: khóa từng Order quá hạn và kiểm tra lại (job định kỳ hoặc
        // request khác có thể vừa expire cùng Order), tránh lệch version với bản đã nạp sẵn
        orderRepository.findExpiredIdsByBuyer(buyerId, UNPAID_ORDER_STATUSES, now).stream()
                .sorted()
                .forEach(orderId -> expireRelatedOrder(orderId, now));
        Page<Order> orders = orderRepository.findByBuyerIdAndStatusIn(
                buyerId, statuses, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return PageResponse.<OrderResponse>builder()
                .content(orderMapper.toResponses(orders.getContent()))
                .page(orders.getNumber())
                .size(orders.getSize())
                .totalElements(orders.getTotalElements())
                .totalPages(orders.getTotalPages())
                .build();
    }

    @Transactional
    public int expireDueOrders() {
        Instant now = Instant.now();
        List<Order> expiredOrders = orderRepository.findExpiredForUpdate(UNPAID_ORDER_STATUSES, now);
        expiredOrders.forEach(order -> expireOrder(order, now));
        return expiredOrders.size();
    }

    @Transactional
    public void cancelUnpaidOrdersForEvent(UUID eventId) {
        lockEvent(eventId);
        jdbcTemplate.update(
                "INSERT INTO cancelled_events (event_id, cancelled_at) VALUES (?, now()) " +
                        "ON CONFLICT (event_id) DO NOTHING",
                eventId
        );
        // Lấy id rồi mới khóa từng Order (xem OrderRepository.findIdsByEventIdAndStatusIn)
        for (UUID orderId : orderRepository.findIdsByEventIdAndStatusIn(eventId, UNPAID_ORDER_STATUSES)
                .stream().sorted().toList()) {
            orderRepository.findByIdForUpdate(orderId).ifPresent(order -> {
                if (UNPAID_ORDER_STATUSES.contains(order.getStatus())) {
                    cancelOrder(order);
                }
            });
        }
        // P4: đơn đã thanh toán của event bị hủy được hoàn 100% (D16). Chạy lại không tạo refund thứ
        // hai vì Order đã rời trạng thái PAID sau lần đầu.
        for (UUID orderId : orderRepository.findIdsByEventIdAndStatusIn(eventId, PAID_ORDER_STATUSES)
                .stream().sorted().toList()) {
            orderRepository.findByIdForUpdate(orderId).ifPresent(order -> {
                if (PAID_ORDER_STATUSES.contains(order.getStatus())) {
                    refundPaidOrder(order, RefundReason.EVENT_CANCELLED, 100);
                }
            });
        }
    }

    @Transactional
    public AvailabilityResponse getAvailability(UUID eventId, ValidatedCatalog catalog) {
        Instant now = Instant.now();
        expireStaleHoldsForEvent(catalog, now);

        List<ProductCatalog.Seat> productSeats = catalog.seats().values().stream().toList();
        List<Hold> seatHolds = productSeats.isEmpty()
                ? List.of()
                : holdRepository.findBySeatIdInAndStatusIn(
                        productSeats.stream().map(ProductCatalog.Seat::id).toList(),
                        List.of(HoldStatus.HELD, HoldStatus.CONFIRMED, HoldStatus.PURCHASED)
                );
        var holdBySeat = new java.util.HashMap<UUID, Hold>();
        seatHolds.stream().filter(hold -> hold.getStatus() == HoldStatus.PURCHASED
                        || hold.getExpiresAt().isAfter(now))
                .forEach(hold -> holdBySeat.put(hold.getSeatId(), hold));

        List<AvailabilityResponse.SeatAvailability> seats = productSeats.stream()
                .map(seat -> {
                    Hold hold = holdBySeat.get(seat.id());
                    String status;
                    if (!"PUBLISHED".equals(catalog.event().status()) || !"ACTIVE".equals(seat.status())) {
                        status = "UNAVAILABLE";
                    } else if (hold == null) {
                        status = "AVAILABLE";
                    } else if (hold.getStatus() == HoldStatus.PURCHASED) {
                        status = "SOLD";
                    } else {
                        status = "HELD";
                    }
                    return AvailabilityResponse.SeatAvailability.builder()
                            .seatId(seat.id())
                            .status(status)
                            .build();
                }).toList();

        List<AvailabilityResponse.TicketTypeAvailability> ticketTypes = new ArrayList<>();
        for (ProductCatalog.TicketType type : catalog.event().ticketTypes()) {
            if ("GENERAL_ADMISSION".equals(type.category())) {
                TicketInventory inventory = ensureInventory(type, eventId);
                int available = Math.max(0,
                        inventory.getTotalQuantity() - inventory.getHeldQuantity() - inventory.getSoldQuantity());
                if (!"PUBLISHED".equals(catalog.event().status())
                        || (catalog.event().salesStartAt() != null && catalog.event().salesStartAt().isAfter(now))
                        || (catalog.event().salesEndAt() != null && !catalog.event().salesEndAt().isAfter(now))) {
                    available = 0;
                }
                ticketTypes.add(AvailabilityResponse.TicketTypeAvailability.builder()
                        .ticketTypeId(type.id())
                        .category(type.category())
                        .totalQuantity(inventory.getTotalQuantity())
                        .availableQuantity(available)
                        .build());
            } else if ("SEATED".equals(type.category())) {
                List<ProductCatalog.Seat> seatsForType = productSeats.stream()
                        .filter(seat -> seat.ticketType() != null && type.id().equals(seat.ticketType().id()))
                        .toList();
                int total = (int) seatsForType.stream().filter(seat -> "ACTIVE".equals(seat.status())).count();
                int available = (int) seatsForType.stream()
                        .filter(seat -> "ACTIVE".equals(seat.status()))
                        .filter(seat -> "AVAILABLE".equals(seatStatus(seat.id(), holdBySeat, catalog.event().status())))
                        .count();
                ticketTypes.add(AvailabilityResponse.TicketTypeAvailability.builder()
                        .ticketTypeId(type.id())
                        .category(type.category())
                        .totalQuantity(total)
                        .availableQuantity(available)
                        .build());
            }
        }
        return AvailabilityResponse.builder().eventId(eventId).seats(seats).ticketTypes(ticketTypes).build();
    }

    private String seatStatus(UUID seatId, java.util.Map<UUID, Hold> holdBySeat, String eventStatus) {
        if (!"PUBLISHED".equals(eventStatus)) {
            return "UNAVAILABLE";
        }
        Hold hold = holdBySeat.get(seatId);
        if (hold == null) {
            return "AVAILABLE";
        }
        return hold.getStatus() == HoldStatus.PURCHASED ? "SOLD" : "HELD";
    }

    private void appendItems(Order order, List<OrderItemRequest> requests, ValidatedCatalog catalog, Instant now) {
        if (!order.getHoldExpiresAt().isAfter(now)) {
            expireOrder(order, now);
            throw new AppException(OrderErrorCode.HOLD_EXPIRED);
        }
        requireCapacityLeft(order, requests.stream().mapToInt(OrderItemRequest::getQuantity).sum());
        for (OrderItemRequest request : requests) {
            ProductCatalog.TicketType type = catalog.ticketTypes().get(request.getTicketTypeId());
            if (type == null) {
                throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
            }
            if ("GENERAL_ADMISSION".equals(type.category())) {
                ensureInventory(type, order.getEventId());
                if (ticketInventoryRepository.tryHold(type.id(), request.getQuantity(), now) != 1) {
                    throw new AppException(OrderErrorCode.CART_ITEMS_UNAVAILABLE);
                }
                OrderItem existing = orderItemRepository
                        .findByOrderIdAndTicketTypeIdAndItemStatusAndCategorySnapshot(
                                order.getId(), type.id(), OrderItemStatus.ACTIVE, "GENERAL_ADMISSION"
                        )
                        .orElse(null);
                if (existing != null) {
                    Hold existingHold = holdRepository.findByOrderItemId(existing.getId())
                            .orElseThrow(() -> new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY));
                    if (existingHold.getStatus() != HoldStatus.HELD
                            || !existingHold.getExpiresAt().isAfter(now)) {
                        throw new AppException(OrderErrorCode.HOLD_EXPIRED);
                    }
                    BigDecimal addedSubtotal = existing.getUnitPriceSnapshot()
                            .multiply(BigDecimal.valueOf(request.getQuantity()));
                    existing.setQuantity(existing.getQuantity() + request.getQuantity());
                    existing.setSubtotal(existing.getSubtotal().add(addedSubtotal));
                    existingHold.setQuantity(existingHold.getQuantity() + request.getQuantity());
                    order.setTotalAmount(order.getTotalAmount().add(addedSubtotal));
                    orderItemRepository.saveAndFlush(existing);
                    holdRepository.saveAndFlush(existingHold);
                    continue;
                }
            } else {
                acquireSeatLock(order, request.getSeatId());
            }

            OrderItem item = orderItemRepository.saveAndFlush(
                    catalogValidationService.toOrderItem(order.getId(), request, catalog)
            );
            Hold hold = holdRepository.saveAndFlush(Hold.builder()
                    .orderId(order.getId())
                    .orderItemId(item.getId())
                    .ticketTypeId(type.id())
                    .seatId(request.getSeatId())
                    .quantity(request.getQuantity())
                    .status(HoldStatus.HELD)
                    .expiresAt(order.getHoldExpiresAt())
                    .build());
            if (request.getSeatId() == null) {
                setRedisMarkerAfterRollback(hold, Duration.between(now, hold.getExpiresAt()));
            }
            order.setTotalAmount(order.getTotalAmount().add(item.getSubtotal()));
        }
    }

    private TicketInventory ensureInventory(ProductCatalog.TicketType type, UUID eventId) {
        try {
            ticketInventoryRepository.insertIfAbsent(type.id(), eventId, type.totalQuantity());
        } catch (RuntimeException ex) {
            log.error("Không thể khởi tạo inventory cho ticketTypeId={}", type.id(), ex);
            throw new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY);
        }
        TicketInventory inventory = ticketInventoryRepository.findById(type.id())
                .orElseThrow(() -> new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY));
        if (!eventId.equals(inventory.getEventId()) || inventory.getTotalQuantity() != type.totalQuantity()) {
            log.error(
                    "Inventory does not match Product catalog: eventId={}, ticketTypeId={}, " +
                            "inventoryEventId={}, inventoryTotal={}, catalogTotal={}, held={}, sold={}",
                    eventId, type.id(), inventory.getEventId(), inventory.getTotalQuantity(),
                    type.totalQuantity(), inventory.getHeldQuantity(), inventory.getSoldQuantity()
            );
            throw new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY);
        }
        return inventory;
    }

    private void acquireSeatLock(Order order, UUID seatId) {
        if (seatId == null) {
            throw new AppException(OrderErrorCode.INVALID_ORDER_ITEMS);
        }
        String key = seatRedisKey(seatId);
        Duration remaining = Duration.between(Instant.now(), order.getHoldExpiresAt());
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, order.getId().toString(), remaining);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new AppException(OrderErrorCode.CART_ITEMS_UNAVAILABLE);
            }
        } catch (AppException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("Không thể tạo Redis hold cho seatId={}", seatId, ex);
            throw new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY);
        }
        registerDeleteOnRollback(key);
    }

    private void setRedisMarkerAfterRollback(Hold hold, Duration ttl) {
        String key = redisKey(hold);
        registerDeleteOnRollback(key);
        try {
            redisTemplate.opsForValue().set(key, hold.getId().toString(), ttl);
        } catch (RuntimeException ex) {
            log.error("Không thể tạo Redis TTL marker cho holdId={}", hold.getId(), ex);
            throw new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY);
        }
    }

    private void registerDeleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteRedisKey(key);
                }
            }
        });
    }

    private void deleteRedisAfterCommit(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteRedisKey(key);
            }
        });
    }

    private void deleteRedisKey(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Không thể dọn Redis hold key={}", key, ex);
        }
    }

    private String redisKey(Hold hold) {
        return hold.getSeatId() == null ? "order:hold:ga:" + hold.getId() : seatRedisKey(hold.getSeatId());
    }

    private String seatRedisKey(UUID seatId) {
        return "order:hold:seat:" + seatId;
    }

    private void persistIdempotency(UUID buyerId, String operation, String key, String hash, UUID orderId) {
        idempotencyRecordRepository.saveAndFlush(IdempotencyRecord.builder()
                .buyerId(buyerId)
                .operation(operation)
                .idempotencyKey(key)
                .requestHash(hash)
                .orderId(orderId)
                .build());
    }

    private OrderResponse replayResponse(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new AppException(OrderErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        Order order = orderRepository.findById(record.getOrderId())
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        return orderMapper.toResponse(order);
    }

    private void publishAfterCommit(Order order, UUID buyerId, UUID eventId, List<OrderItemRequest> requests) {
        int quantity = requests.stream().mapToInt(OrderItemRequest::getQuantity).sum();
        CartItemAddedEvent event = new CartItemAddedEvent(
                order.getId(), buyerId, eventId, requests.size(), quantity, Instant.now()
        );
        orderEventPublisher.recordCartItemAdded(event);
    }

    private void expireStaleHoldsForEvent(ValidatedCatalog catalog, Instant now) {
        List<UUID> ticketTypeIds = catalog.event().ticketTypes().stream().map(ProductCatalog.TicketType::id).toList();
        if (ticketTypeIds.isEmpty()) {
            return;
        }
        // Khóa từng Order theo id tăng dần: các request cùng expire nhiều Order luôn khóa cùng thứ tự
        holdRepository.findOrderIdsWithExpiredHolds(ticketTypeIds, ACTIVE_HOLD_STATUSES, now).stream()
                .sorted()
                .forEach(orderId -> expireRelatedOrder(orderId, now));
    }

    private void expireRelatedOrder(UUID orderId, Instant now) {
        orderRepository.findByIdForUpdate(orderId).ifPresent(order -> {
            if (UNPAID_ORDER_STATUSES.contains(order.getStatus()) && isExpired(order, now)) {
                expireOrder(order, now);
            }
        });
    }

    private void expireOrder(Order order, Instant now) {
        releaseActiveHolds(order, HoldStatus.EXPIRED, OrderItemStatus.EXPIRED);
        order.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(order);
        log.debug("Order hết hạn, orderId={}", order.getId());
    }

    private void cancelOrder(Order order) {
        releaseActiveHolds(order, HoldStatus.RELEASED, OrderItemStatus.CANCELLED);
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
    }

    private void releaseActiveHolds(Order order, HoldStatus targetStatus, OrderItemStatus itemStatus) {
        List<Hold> holds = holdRepository.findActiveByOrderIdForUpdate(order.getId(), ACTIVE_HOLD_STATUSES);
        for (Hold hold : holds) {
            releaseInventory(hold);
            hold.setStatus(targetStatus);
            orderItemRepository.findByIdAndOrderId(hold.getOrderItemId(), order.getId())
                    .ifPresent(item -> item.setItemStatus(itemStatus));
            deleteRedisAfterCommit(redisKey(hold));
        }
    }

    private void releaseInventory(Hold hold) {
        if (hold.getSeatId() == null) {
            releaseInventoryQuantity(hold, hold.getQuantity());
        }
    }

    private void releaseInventoryQuantity(Hold hold, int quantity) {
        int updated = ticketInventoryRepository.releaseHold(hold.getTicketTypeId(), quantity, Instant.now());
        if (updated != 1) {
            log.error(
                    "Could not release GA inventory: holdId={}, ticketTypeId={}, quantity={}; " +
                            "inventory row may be missing or held quantity is too small",
                    hold.getId(), hold.getTicketTypeId(), quantity
            );
            throw new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY);
        }
    }

    private boolean isExpired(Order order, Instant now) {
        return order.getHoldExpiresAt() == null || !order.getHoldExpiresAt().isAfter(now);
    }

    private void lockEvent(UUID eventId) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { },
                eventId.toString()
        );
    }

    private void ensureEventNotCancelled(UUID eventId) {
        Boolean cancelled = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cancelled_events WHERE event_id = ?)",
                Boolean.class,
                eventId
        );
        if (Boolean.TRUE.equals(cancelled)) {
            throw new AppException(OrderErrorCode.EVENT_NOT_AVAILABLE);
        }
    }
}
