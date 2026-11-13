package com.dev.order_service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.client.ProductCatalogClient;
import com.dev.order_service.dto.request.CreateOrderRequest;
import com.dev.order_service.dto.request.OrderItemRequest;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.ProductCatalog;
import com.dev.order_service.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockito.Mockito.when;

/**
 * Nền chung cho integration test order-service: PostgreSQL/Redis thật (profile "test"),
 * product-service thay bằng mock. Mỗi test dựng một event mới (id ngẫu nhiên) nên dữ liệu
 * giữa các test không đụng nhau dù dùng chung DB.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractOrderIT {

    protected static final BigDecimal SEAT_PRICE = new BigDecimal("150.00");
    protected static final BigDecimal GA_PRICE = new BigDecimal("20.00");

    @MockitoBean
    protected ProductCatalogClient productCatalogClient;

    @Autowired
    protected OrderService orderService;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    protected UUID eventId;
    protected UUID seatedTypeId;
    protected UUID gaTypeId;
    protected List<UUID> seatIds;

    @BeforeEach
    void newCatalog() {
        catalog(4, 10);
    }

    /** Dựng event PUBLISHED đang mở bán với {@code seats} ghế SEATED và {@code gaCapacity} vé GA. */
    protected void catalog(int seats, int gaCapacity) {
        eventId = UUID.randomUUID();
        seatedTypeId = UUID.randomUUID();
        gaTypeId = UUID.randomUUID();
        seatIds = new ArrayList<>();
        List<ProductCatalog.Seat> seatList = new ArrayList<>();
        for (int i = 1; i <= seats; i++) {
            UUID id = UUID.randomUUID();
            seatIds.add(id);
            seatList.add(new ProductCatalog.Seat(id, "Floor", "A", "%02d".formatted(i), "A-%02d".formatted(i),
                    "ACTIVE", new ProductCatalog.TicketBrief(seatedTypeId, "VIP", SEAT_PRICE)));
        }
        when(productCatalogClient.getEvent(eventId)).thenReturn(new ProductCatalog(eventId, "PUBLISHED",
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS), true,
                List.of(new ProductCatalog.TicketType(seatedTypeId, "SEATED", "VIP", SEAT_PRICE, seats),
                        new ProductCatalog.TicketType(gaTypeId, "GENERAL_ADMISSION", "GA", GA_PRICE, gaCapacity))));
        when(productCatalogClient.getSeatMap(eventId))
                .thenReturn(new ProductCatalog.SeatMap(UUID.randomUUID(), seatList));
    }

    protected OrderResponse createCart(UUID buyerId, String key, OrderItemRequest... items) {
        return orderService.create(buyerId, new CreateOrderRequest(eventId, List.of(items)), key);
    }

    protected OrderItemRequest seat(int index) {
        return new OrderItemRequest(seatedTypeId, seatIds.get(index), 1);
    }

    protected OrderItemRequest ga(int quantity) {
        return new OrderItemRequest(gaTypeId, null, quantity);
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    // ---------- truy vấn trạng thái DB để kiểm tra bất biến ----------

    protected int activeSeatHolds(UUID seatId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM holds WHERE seat_id = ? AND status IN ('HELD','CONFIRMED','PURCHASED')",
                Integer.class, seatId);
    }

    protected int heldGa() {
        Integer held = jdbc.queryForObject(
                "SELECT COALESCE(max(held_quantity), 0) FROM ticket_inventory WHERE ticket_type_id = ?",
                Integer.class, gaTypeId);
        return held == null ? 0 : held;
    }

    /** Tổng quantity các hold GA còn hiệu lực — phải luôn bằng held_quantity của inventory. */
    protected int sumActiveGaHolds() {
        return jdbc.queryForObject(
                "SELECT COALESCE(sum(quantity), 0) FROM holds WHERE ticket_type_id = ? AND status IN ('HELD','CONFIRMED')",
                Integer.class, gaTypeId);
    }

    protected String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    /**
     * Mô phỏng hết hạn mà không phải chờ 10 phút: lùi deadline Order/hold trong DB và xóa key Redis
     * của ghế — ngoài thực tế TTL Redis được đặt bằng thời gian còn lại của hold nên hết cùng lúc.
     */
    protected void forceExpire(UUID orderId) {
        jdbc.update("UPDATE orders SET hold_expires_at = now() - interval '1 second' WHERE id = ?", orderId);
        jdbc.update("UPDATE holds SET expires_at = now() - interval '1 second' WHERE order_id = ?", orderId);
        jdbc.queryForList("SELECT seat_id FROM holds WHERE order_id = ? AND seat_id IS NOT NULL", UUID.class, orderId)
                .forEach(seatId -> redis.delete("order:hold:seat:" + seatId));
    }

    // ---------- chạy song song ----------

    protected static <T> List<Outcome<T>> runConcurrently(int threads, Callable<T> task) throws InterruptedException {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return runConcurrently(tasks);
    }

    /** Mọi tác vụ chờ cùng một latch rồi xuất phát đồng thời. */
    protected static <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    outcomes.add(new Outcome<>(future.get(), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(null, e.getCause()));
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    protected record Outcome<T>(T value, Throwable error) {
        public boolean succeeded() {
            return error == null;
        }

        /** Mã lỗi nghiệp vụ (vd 4006), hoặc -1 nếu là lỗi không mong đợi. */
        public int errorCode() {
            return error instanceof AppException app ? app.getErrorCode().getCode() : -1;
        }
    }
}
