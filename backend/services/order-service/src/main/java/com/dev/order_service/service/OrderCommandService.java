package com.dev.order_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.dto.request.OrderItemRequest;
import com.dev.order_service.dto.response.AvailabilityResponse;
import com.dev.order_service.dto.response.OrderResponse;
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
        if (!UNPAID_ORDER_STATUSES.contains(order.getStatus())) {
            throw new AppException(OrderErrorCode.ORDER_STATE_CONFLICT);
        }
        cancelOrder(order);
        return orderMapper.toResponse(order);
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
    public List<OrderResponse> getActiveOrders(UUID buyerId) {
        Instant now = Instant.now();
        // Expire trước khi nạp danh sách: khóa từng Order quá hạn và kiểm tra lại (job định kỳ hoặc
        // request khác có thể vừa expire cùng Order), tránh lệch version với bản đã nạp sẵn
        orderRepository.findExpiredIdsByBuyer(buyerId, UNPAID_ORDER_STATUSES, now).stream()
                .sorted()
                .forEach(orderId -> expireRelatedOrder(orderId, now));
        List<Order> orders = orderRepository.findByBuyerIdAndStatusInOrderByCreatedAtDesc(
                buyerId, UNPAID_ORDER_STATUSES
        );
        return orderMapper.toResponses(orders.stream()
                .filter(order -> UNPAID_ORDER_STATUSES.contains(order.getStatus()))
                .toList());
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
