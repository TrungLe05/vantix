package com.dev.product_service.services;

import com.dev.commonlib_kafka.event.EventCancelledEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox event_outbox trên PostgreSQL thật, Kafka giả lập: gửi thành công, backoff lũy thừa,
 * dead-letter sau max-attempts (mặc định 10), dừng lô khi lỗi.
 */
@SpringBootTest
@ActiveProfiles("test")
class EventOutboxPublisherIntegrationTest {

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EventOutboxPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void isolateFromOtherTests() {
        jdbc.update("UPDATE event_outbox SET published_at = now() WHERE published_at IS NULL");
    }

    @Autowired
    private EventService eventService;

    @Autowired
    private VenueService venueService;

    @Test
    void cancellingAnEventIsPublishedWithItsCancelTimestamp() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UUID eventId = createDraftEvent();

        eventService.cancel(eventId);
        publisher.publishPendingEvents();

        Map<String, Object> outbox = jdbc.queryForMap("SELECT * FROM event_outbox WHERE event_id = ?", eventId);
        assertThat(outbox.get("published_at")).isNotNull();
        assertThat(outbox.get("attempt_count")).isEqualTo(0);
        verify(kafkaTemplate).send(eq("product.event-cancelled"), eq(eventId.toString()),
                argThat(payload -> payload instanceof EventCancelledEvent e && e.eventId().equals(eventId)
                        && e.cancelledAt() != null));
    }

    @Test
    void unparsablePayloadIsRetriedInsteadOfBlockingTheOutbox() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UUID broken = UUID.randomUUID();
        jdbc.update("INSERT INTO event_outbox (id, event_id, event_type, payload, created_at) " +
                "VALUES (?, ?, 'product.event-cancelled', '{\"cancelledAt\":\"yesterday\"}', clock_timestamp())",
                broken, UUID.randomUUID());

        publisher.publishPendingEvents();

        assertThat(row(broken)).containsEntry("attempt_count", 1).containsEntry("last_error", "DateTimeParseException");
        UUID healthy = insertOutbox();
        publisher.publishPendingEvents(); // bản ghi hỏng chưa tới hạn retry nên không chặn bản ghi lành
        assertThat(row(healthy).get("published_at")).isNotNull();
    }

    @Test
    void successfulSendMarksPublished() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UUID id = insertOutbox();

        publisher.publishPendingEvents();

        assertThat(row(id).get("published_at")).isNotNull();
    }

    @Test
    void failuresBackOffExponentially() {
        failSends();
        UUID id = insertOutbox();

        publisher.publishPendingEvents();
        assertThat(row(id)).containsEntry("attempt_count", 1).containsEntry("last_error", "TimeoutException");
        assertThat(delaySeconds(id)).isBetween(3.5, 6.0);

        makeDue(id);
        publisher.publishPendingEvents();
        assertThat(delaySeconds(id)).isBetween(8.5, 11.0);
    }

    @Test
    void lastAllowedFailureDeadLettersAndStopsRetrying() {
        failSends();
        UUID id = insertOutbox();
        jdbc.update("UPDATE event_outbox SET attempt_count = 9 WHERE id = ?", id);

        publisher.publishPendingEvents();
        assertThat(row(id).get("dead_at")).isNotNull();
        assertThat(meterRegistry.get("outbox.dead").tag("outbox", "event_outbox").gauge().value())
                .isGreaterThanOrEqualTo(1.0);

        clearInvocations(kafkaTemplate);
        makeDue(id);
        publisher.publishPendingEvents();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void batchStopsAtFirstFailure() {
        failSends();
        UUID first = insertOutbox();
        UUID second = insertOutbox();

        publisher.publishPendingEvents();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
        assertThat(row(first).get("attempt_count")).isEqualTo(1);
        assertThat(row(second).get("attempt_count")).isEqualTo(0);
    }

    private UUID createDraftEvent() {
        com.dev.product_service.dto.request.CreateVenueRequest venue = new com.dev.product_service.dto.request.CreateVenueRequest();
        venue.setName("Outbox Venue");
        venue.setAddress("1 Test St");
        venue.setCity("HCM");
        UUID venueId = venueService.create(venue).getId();
        com.dev.product_service.dto.request.CreateEventRequest event = new com.dev.product_service.dto.request.CreateEventRequest();
        Instant start = Instant.now().plus(java.time.Duration.ofDays(30));
        event.setVenueId(venueId);
        event.setName("Outbox Event");
        event.setStartAt(start);
        event.setEndAt(start.plus(java.time.Duration.ofHours(2)));
        return eventService.create(event).getId();
    }

    private void failSends() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down", new TimeoutException())));
    }

    private UUID insertOutbox() {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO event_outbox (id, event_id, event_type, payload, created_at) " +
                        "VALUES (?, ?, 'product.event-cancelled', CAST(? AS jsonb), clock_timestamp())",
                id, eventId, "{\"eventId\":\"" + eventId + "\",\"cancelledAt\":\"" + Instant.now() + "\"}");
        return id;
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE event_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
    }

    private double delaySeconds(UUID id) {
        return jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (next_attempt_at - now())) FROM event_outbox WHERE id = ?",
                Double.class, id);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM event_outbox WHERE id = ?", id);
    }
}
