package com.dev.product_service;

import com.dev.product_service.repository.SeatRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product-service qua HTTP (MockMvc + security filter thật, header gateway giả lập) trên PostgreSQL thật:
 * publish gom lý do (PR05), ẩn DRAFT ở API công khai (PR07/PR08/PR11), PR10 all-or-nothing, ràng buộc PR12.
 * Mỗi test tạo venue/event mới nên không phụ thuộc dữ liệu có sẵn trong DB test.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductCatalogIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private SeatRepository seatRepository;

    private MockMvc mvc;
    private UUID venueId;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static boolean cleaned;

    @BeforeEach
    void setUp() throws Exception {
        if (!cleaned) {
            // Danh sách admin/public phân trang theo startAt: dữ liệu tích lũy qua nhiều lần chạy local đẩy event
            // mới ra ngoài trang. Dọn một lần mỗi lần chạy — chỉ trên DB test, không bao giờ trên DB dev.
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                    .isEqualTo("products_service_test");
            jdbc.execute("TRUNCATE event_outbox, seats, ticket_types, seat_maps, events, venues CASCADE");
            cleaned = true;
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        venueId = id(admin(post("/venues"),
                "{\"name\":\"IT Venue\",\"address\":\"1 Test St\",\"city\":\"HCM\"}").andExpect(status().isCreated()));
    }

    // ---------- PR05: publish gom lý do ----------

    @Test
    void publishCollectsEveryFailingReasonInOneResponse() throws Exception {
        UUID eventId = createEvent();
        createSeatMap(eventId);
        addSeats(eventId, seat("A", "01"), seat("A", "02"));

        admin(patch("/events/" + eventId + "/publish"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3105))
                .andExpect(jsonPath("$.result.reasons", containsInAnyOrder(
                        "Chưa có hạng vé nào",
                        "Còn 2 ghế chưa được gán hạng vé")));
    }

    @Test
    void publishSucceedsOnceConditionsAreMetAndOnlyOnce() throws Exception {
        UUID eventId = createEvent();
        createGa(eventId, 100).andExpect(status().isCreated());

        admin(patch("/events/" + eventId + "/publish"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("PUBLISHED"));
        admin(patch("/events/" + eventId + "/publish"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3104));
    }

    @Test
    void publishRequiresAdminRole() throws Exception {
        UUID eventId = createEvent();
        mvc.perform(patch("/events/" + eventId + "/publish")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "BUYER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));
    }

    // ---------- Ẩn DRAFT ở API công khai ----------

    @Test
    void draftEventIsHiddenFromPublicApisButVisibleToAdmin() throws Exception {
        UUID eventId = createEvent();
        createSeatMap(eventId);

        mvc.perform(get("/events/" + eventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3101));
        mvc.perform(get("/events/" + eventId + "/seat-map"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3101));
        mvc.perform(get("/events").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[*].id", not(hasItem(eventId.toString()))));
        admin(get("/events/admin/list").param("status", "DRAFT").param("size", "100"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[*].id", hasItem(eventId.toString())));
    }

    @Test
    void publishedEventBecomesVisibleOnPublicApis() throws Exception {
        UUID eventId = createEvent();
        createGa(eventId, 10).andExpect(status().isCreated());
        admin(patch("/events/" + eventId + "/publish"), null).andExpect(status().isOk());

        mvc.perform(get("/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("PUBLISHED"));
    }

    // ---------- PR10: thêm ghế hàng loạt all-or-nothing ----------

    @Test
    void addSeatsBatchIsAllOrNothingWhenConflictingWithExistingSeat() throws Exception {
        UUID eventId = createEvent();
        createSeatMap(eventId);
        addSeats(eventId, seat("A", "01"), seat("A", "02")).andExpect(status().isCreated());

        addSeats(eventId, seat("A", "03"), seat("A", "01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3203));

        // A-03 không được tạo lẻ: batch lỗi phải rollback toàn bộ
        assertThat(seatsOf(eventId)).containsExactlyInAnyOrder("A-01", "A-02");
    }

    @Test
    void addSeatsRejectsDuplicatesInsidePayload() throws Exception {
        UUID eventId = createEvent();
        createSeatMap(eventId);

        addSeats(eventId, seat("A", "01"), seat("A", "01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3203));
        assertThat(seatsOf(eventId)).isEmpty();
    }

    @Test
    void addSeatsRequiresSeatMapAndDraft() throws Exception {
        UUID noMap = createEvent();
        addSeats(noMap, seat("A", "01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3202));

        UUID published = createEvent();
        createSeatMap(published);
        createGa(published, 5).andExpect(status().isCreated());
        admin(patch("/events/" + published + "/publish"), null).andExpect(status().isOk());
        addSeats(published, seat("A", "01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3102));
    }

    // ---------- PR12: ràng buộc hạng vé ----------

    @Test
    void seatedTicketTypeAssignsSeatsAndDerivesQuantity() throws Exception {
        UUID eventId = createEvent();
        createSeatMap(eventId);
        List<UUID> seats = seatIds(addSeats(eventId, seat("A", "01"), seat("A", "02")));

        createSeated(eventId, seats)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result.totalQuantity").value(2));
    }

    @Test
    void seatedTicketTypeRejectsInvalidSeats() throws Exception {
        UUID eventId = createEvent();
        createSeatMap(eventId);
        List<UUID> seats = seatIds(addSeats(eventId, seat("A", "01"), seat("A", "02")));

        UUID otherEvent = createEvent();
        createSeatMap(otherEvent);
        List<UUID> foreignSeats = seatIds(addSeats(otherEvent, seat("B", "01")));

        createSeated(eventId, List.of()).andExpect(jsonPath("$.code").value(3303));
        createSeated(eventId, List.of(UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3204));
        createSeated(eventId, List.of(seats.get(0), foreignSeats.get(0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3206));

        createSeated(eventId, List.of(seats.get(0))).andExpect(status().isCreated());
        createSeated(eventId, seats).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3205));
    }

    @Test
    void ticketTypeFieldsMustMatchCategory() throws Exception {
        UUID eventId = createEvent();
        createSeatMap(eventId);
        List<UUID> seats = seatIds(addSeats(eventId, seat("A", "01")));

        createGa(eventId, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(3302));
        // Gửi field không thuộc category → 1001 (P2_product_api_design.md PR12)
        admin(post("/events/" + eventId + "/ticket-types"),
                "{\"category\":\"SEATED\",\"name\":\"VIP\",\"price\":10,\"totalQuantity\":5,\"seatIds\":[\"" + seats.get(0) + "\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        admin(post("/events/" + eventId + "/ticket-types"),
                "{\"category\":\"GENERAL_ADMISSION\",\"name\":\"GA\",\"price\":10,\"totalQuantity\":5,\"seatIds\":[\"" + seats.get(0) + "\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void concurrentTicketTypesClaimingSameSeatHaveOneWinner() throws Exception {
        UUID eventId = createEvent();
        createSeatMap(eventId);
        List<UUID> seats = seatIds(addSeats(eventId, seat("A", "01"), seat("A", "02")));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return createSeated(eventId, seats).andReturn();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                statuses.add(f.get().getResponse().getStatus());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s != 201).allMatch(s -> s == 409);
        } finally {
            pool.shutdownNow();
        }

        // Cả hai ghế cùng thuộc một hạng vé duy nhất (không bị hai request chia nhau)
        assertThat(seatRepository.findAllById(seats))
                .extracting(s -> s.getTicketTypeId())
                .doesNotContainNull()
                .hasSize(2)
                .containsOnly(seatRepository.findAllById(seats).get(0).getTicketTypeId());
    }

    // ---------- helpers ----------

    private UUID createEvent() throws Exception {
        Instant start = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String body = """
                {"venueId":"%s","name":"IT Event","startAt":"%s","endAt":"%s",
                 "salesStartAt":"%s","salesEndAt":"%s"}
                """.formatted(venueId, start, start.plus(3, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                start.minus(1, ChronoUnit.HOURS));
        return id(admin(post("/events"), body).andExpect(status().isCreated()));
    }

    private void createSeatMap(UUID eventId) throws Exception {
        admin(post("/events/" + eventId + "/seat-map"), "{\"name\":\"Main\"}").andExpect(status().isCreated());
    }

    private ResultActions addSeats(UUID eventId, String... seats) throws Exception {
        return admin(post("/events/" + eventId + "/seat-map/seats"),
                "{\"seats\":[" + String.join(",", seats) + "]}");
    }

    private ResultActions createSeated(UUID eventId, List<UUID> seatIds) throws Exception {
        String ids = String.join(",", seatIds.stream().map(id -> "\"" + id + "\"").toList());
        return admin(post("/events/" + eventId + "/ticket-types"),
                "{\"category\":\"SEATED\",\"name\":\"VIP\",\"price\":100,\"seatIds\":[" + ids + "]}");
    }

    private ResultActions createGa(UUID eventId, int quantity) throws Exception {
        return admin(post("/events/" + eventId + "/ticket-types"),
                "{\"category\":\"GENERAL_ADMISSION\",\"name\":\"GA\",\"price\":20,\"totalQuantity\":" + quantity + "}");
    }

    private static String seat(String row, String number) {
        return "{\"section\":\"VIP\",\"rowLabel\":\"" + row + "\",\"seatNumber\":\"" + number + "\"}";
    }

    private List<String> seatsOf(UUID eventId) {
        return seatRepository.findAll().stream()
                .filter(s -> seatMapEventId(s.getSeatMapId()).equals(eventId))
                .map(s -> s.getDisplayLabel())
                .toList();
    }

    @Autowired
    private com.dev.product_service.repository.SeatMapRepository seatMapRepository;

    private UUID seatMapEventId(UUID seatMapId) {
        return seatMapRepository.findById(seatMapId).orElseThrow().getEventId();
    }

    private List<UUID> seatIds(ResultActions result) throws Exception {
        JsonNode seats = jsonMapper.readTree(result.andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).path("result").path("seats");
        List<UUID> ids = new ArrayList<>();
        seats.forEach(s -> ids.add(UUID.fromString(s.path("id").asString())));
        return ids;
    }

    private UUID id(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(json).path("result").path("id").asString());
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "ADMIN");
    }

    private ResultActions admin(MockHttpServletRequestBuilder request, String body) throws Exception {
        MockHttpServletRequestBuilder builder = admin(request);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }
}
