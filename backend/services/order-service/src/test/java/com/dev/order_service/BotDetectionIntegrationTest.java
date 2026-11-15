package com.dev.order_service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.device.DeviceHasher;
import com.dev.order_service.device.DeviceIdFilter;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.enums.OrderStatus;
import com.dev.order_service.event.BotScoreEvaluatedEvent;
import com.dev.order_service.event.OrderEventListener;
import com.dev.order_service.service.BotFlagService;
import jakarta.servlet.Filter;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6 phía Order (D37, D38, D41): device hash + lượt xem trong cart.item_added, flag từ
 * bot_score.evaluated, chặn thêm giỏ theo buyer/thiết bị mà không đụng hold cũ, ADMIN review.
 */
class BotDetectionIntegrationTest extends AbstractOrderIT {

    private static final String ADMIN_ID = "00000000-0000-0000-0000-0000000000ad";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private DeviceIdFilter deviceIdFilter;

    @Autowired
    private DeviceHasher deviceHasher;

    @Autowired
    private BotFlagService botFlagService;

    @Autowired
    private OrderEventListener orderEventListener;

    @Autowired
    private tools.jackson.databind.json.JsonMapper jsonMapper;

    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(deviceIdFilter, springSecurityFilterChain)
                .build();
    }

    // ---------- tín hiệu gửi bot-detection ----------

    @Test
    void cartEventCarriesDeviceHashAndFirstViewButNeverTheRawDeviceId() throws Exception {
        UUID buyer = UUID.randomUUID();
        String device = UUID.randomUUID().toString();
        Instant beforeView = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mvc.perform(get("/orders/events/" + eventId + "/availability").header("X-Device-Id", device))
                .andExpect(status().isOk());
        // Lần xem sau không ghi đè lần đầu (NX)
        mvc.perform(get("/orders/events/" + eventId + "/availability").header("X-Device-Id", device))
                .andExpect(status().isOk());

        UUID orderId = orderIdOf(addGaViaApi(buyer, device, 2).andExpect(status().isCreated()));

        String payload = cartPayload(orderId);
        String hash = deviceHasher.hash(device).orElseThrow();
        assertThat(payload).contains("\"deviceHash\":\"" + hash + "\"")
                .contains("\"quantity\":2")
                .contains("\"eventViewedAt\":\"")
                .doesNotContain(device);
        Instant viewedAt = Instant.parse(payload.replaceAll(".*\"eventViewedAt\":\"([^\"]+)\".*", "$1"));
        assertThat(viewedAt).isBetween(beforeView, Instant.now());
    }

    @Test
    void missingOrMalformedDeviceIdIsTreatedAsNoDevice() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID orderId = orderIdOf(addGaViaApi(buyer, "not-a-uuid", 1).andExpect(status().isCreated()));

        String payload = cartPayload(orderId);
        assertThat(payload).contains("\"deviceHash\":null").contains("\"eventViewedAt\":null");
        assertThat(deviceHasher.hash(null)).isEmpty();
        assertThat(deviceHasher.hash(" ")).isEmpty();
        // Không phân biệt hoa/thường: cùng UUID → cùng hash
        String id = UUID.randomUUID().toString();
        assertThat(deviceHasher.hash(id.toUpperCase())).isEqualTo(deviceHasher.hash(id));
    }

    // ---------- flag và chặn ----------

    @Test
    void highRiskBlocksFurtherAddsForBuyerAndDeviceButKeepsExistingHolds() throws Exception {
        UUID bot = UUID.randomUUID();
        String device = UUID.randomUUID().toString();
        String hash = deviceHasher.hash(device).orElseThrow();
        OrderResponse cart = createCart(bot, key(), seat(0), ga(2));

        assertThat(botFlagService.recordEvaluation(evaluation(bot, hash, cart.getId(), "HIGH", "0.71",
                Instant.now()))).isTrue();

        // Hold hiện có giữ nguyên (không phạt oan nếu model sai)
        assertThat(orderStatus(cart.getId())).isEqualTo(OrderStatus.CART.name());
        assertThat(activeSeatHolds(seatIds.get(0))).isEqualTo(1);
        assertThat(heldGa()).isEqualTo(2);

        // Cùng tài khoản: mọi đường thêm vé bị chặn, giảm số lượng vẫn được
        assertError(() -> createCart(bot, key(), seat(1)), 4019);
        assertError(() -> orderService.addItems(bot, cart.getId(),
                new com.dev.order_service.dto.request.AddOrderItemsRequest(List.of(seat(1))), key()), 4019);
        UUID gaItem = cart.getItems().stream().filter(i -> i.getSeatId() == null).findFirst().orElseThrow().getId();
        assertError(() -> orderService.updateItemQuantity(bot, cart.getId(), gaItem, 3), 4019);
        assertThat(orderService.updateItemQuantity(bot, cart.getId(), gaItem, 1).getTotalAmount())
                .isEqualByComparingTo(SEAT_PRICE.add(GA_PRICE));

        // Tài khoản khác trên cùng thiết bị: bị chặn theo thiết bị
        addGaViaApi(UUID.randomUUID(), device, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(4019));
        // Tài khoản khác, thiết bị khác: không bị ảnh hưởng
        addGaViaApi(UUID.randomUUID(), UUID.randomUUID().toString(), 1).andExpect(status().isCreated());
    }

    @Test
    void onlyHighRiskCreatesFlagsAndRepeatsMergeIntoOneActiveRow() {
        UUID buyer = UUID.randomUUID();
        for (String level : List.of("LOW", "MEDIUM", "UNSCORED")) {
            assertThat(botFlagService.recordEvaluation(evaluation(buyer, null, UUID.randomUUID(), level,
                    "UNSCORED".equals(level) ? null : "0.40", Instant.now()))).isFalse();
        }
        assertThat(flagCount(buyer)).isZero();
        createCart(buyer, key(), ga(1));

        botFlagService.recordEvaluation(evaluation(buyer, null, UUID.randomUUID(), "HIGH", "0.62", Instant.now()));
        botFlagService.recordEvaluation(evaluation(buyer, "abc", UUID.randomUUID(), "HIGH", "0.80", Instant.now()));
        botFlagService.recordEvaluation(evaluation(buyer, "def", UUID.randomUUID(), "HIGH", "0.70", Instant.now()));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT count(*) OVER () AS n, hit_count, score, device_hash FROM bot_flags WHERE buyer_id = ?", buyer);
        assertThat(((Number) row.get("n")).intValue()).isEqualTo(1);
        assertThat(row.get("hit_count")).isEqualTo(3);
        assertThat((BigDecimal) row.get("score")).isEqualByComparingTo("0.80");
        // Thiết bị đầu tiên được ghi nhận giữ nguyên
        assertThat(row.get("device_hash")).isEqualTo("abc");
    }

    @Test
    void concurrentHighRiskMessagesForOneBuyerLeaveExactlyOneActiveFlag() throws Exception {
        UUID buyer = UUID.randomUUID();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> botFlagService.recordEvaluation(
                    evaluation(buyer, null, UUID.randomUUID(), "HIGH", "0.75", Instant.now())));
        }
        runConcurrently(tasks);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bot_flags WHERE buyer_id = ? AND status = 'ACTIVE'", Integer.class, buyer))
                .isEqualTo(1);
    }

    @Test
    void listenerAcceptsThePythonPayloadAndIgnoresUnknownFields() {
        UUID buyer = UUID.randomUUID();
        String payload = """
                {"evaluationId":"%s","orderId":"%s","buyerId":"%s","eventId":"%s","deviceHash":null,
                 "anomalyScore":0.6931,"riskLevel":"HIGH","modelVersion":"iforest-test",
                 "features":{"buyer_adds_1m":9,"viewed_event":0},"occurredAt":"%s","evaluatedAt":"%s",
                 "schemaVersion":1}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), buyer, eventId, Instant.now(), Instant.now());
        orderEventListener.onBotScoreEvaluated(payload);
        assertError(() -> createCart(buyer, key(), ga(1)), 4019);
    }

    // ---------- ADMIN review ----------

    @Test
    void adminListsAndClearsFlagsAndReplayedOldScoresDoNotReflag() throws Exception {
        UUID buyer = UUID.randomUUID();
        Instant addedAt = Instant.now();
        botFlagService.recordEvaluation(evaluation(buyer, null, UUID.randomUUID(), "HIGH", "0.66", addedAt));
        UUID flagId = jdbc.queryForObject("SELECT id FROM bot_flags WHERE buyer_id = ?", UUID.class, buyer);

        mvc.perform(get("/orders/bot-flags").param("status", "ACTIVE").param("size", "100")
                        .header("X-User-Id", ADMIN_ID).header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[?(@.id == '" + flagId + "')].features.buyer_adds_1m")
                        .value(9));
        for (String role : List.of("BUYER", "STAFF")) {
            mvc.perform(get("/orders/bot-flags").header("X-User-Id", UUID.randomUUID().toString())
                            .header("X-User-Role", role))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(1004));
        }
        mvc.perform(get("/orders/bot-flags").param("status", "BOGUS")
                        .header("X-User-Id", ADMIN_ID).header("X-User-Role", "ADMIN"))
                .andExpect(jsonPath("$.code").value(1001));

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/orders/bot-flags/" + flagId + "/clear")
                            .header("X-User-Id", ADMIN_ID).header("X-User-Role", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.result.status").value("CLEARED"))
                    .andExpect(jsonPath("$.result.clearedBy").value(ADMIN_ID));
        }
        mvc.perform(post("/orders/bot-flags/" + UUID.randomUUID() + "/clear")
                        .header("X-User-Id", ADMIN_ID).header("X-User-Role", "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4020));

        // Gỡ xong mua lại được
        assertThat(createCart(buyer, key(), ga(1)).getStatus()).isEqualTo(OrderStatus.CART);
        // Kafka phát lại điểm của lần thêm giỏ trước khi gỡ: không khóa lại
        assertThat(botFlagService.recordEvaluation(evaluation(buyer, null, UUID.randomUUID(), "HIGH", "0.90",
                addedAt))).isFalse();
        // Hành vi mới sau khi gỡ: flag mới, dòng cũ vẫn là lịch sử
        assertThat(botFlagService.recordEvaluation(evaluation(buyer, null, UUID.randomUUID(), "HIGH", "0.70",
                Instant.now().plusSeconds(1)))).isTrue();
        assertThat(flagCount(buyer)).isEqualTo(2);
    }

    // ---------- helpers ----------

    private ResultActions addGaViaApi(UUID buyer, String deviceId, int quantity) throws Exception {
        String body = """
                {"eventId":"%s","items":[{"ticketTypeId":"%s","quantity":%d}]}
                """.formatted(eventId, gaTypeId, quantity);
        return mvc.perform(post("/orders")
                .header("X-User-Id", buyer.toString()).header("X-User-Role", "BUYER")
                .header("Idempotency-Key", key()).header("X-Device-Id", deviceId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID orderIdOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(body).path("result").path("id").asString());
    }

    private String cartPayload(UUID orderId) {
        List<String> payloads = jdbc.queryForList(
                "SELECT payload FROM order_event_outbox WHERE order_id = ? AND topic = 'cart.item_added'",
                String.class, orderId);
        assertThat(payloads).hasSize(1);
        return payloads.get(0);
    }

    private int flagCount(UUID buyer) {
        return jdbc.queryForObject("SELECT count(*) FROM bot_flags WHERE buyer_id = ?", Integer.class, buyer);
    }

    private BotScoreEvaluatedEvent evaluation(UUID buyer, String deviceHash, UUID orderId, String level,
                                              String score, Instant occurredAt) {
        return new BotScoreEvaluatedEvent(UUID.randomUUID(), orderId, buyer, eventId, deviceHash,
                score == null ? null : new BigDecimal(score), level, "iforest-test",
                Map.of("buyer_adds_1m", 9, "viewed_event", 0), occurredAt, Instant.now());
    }

    private static void assertError(ThrowableAssert.ThrowingCallable call, int code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }
}
