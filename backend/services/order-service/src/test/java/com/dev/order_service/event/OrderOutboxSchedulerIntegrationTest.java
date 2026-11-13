package com.dev.order_service.event;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox order_event_outbox trên PostgreSQL thật, Kafka giả lập: gửi thành công, backoff lũy thừa,
 * trần backoff, dead-letter sau max-attempts (mặc định 10), dừng lô khi lỗi, gauge.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderOutboxSchedulerIntegrationTest {

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private OrderOutboxScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void isolateFromOtherTests() {
        // DB test dùng chung với các test khác (Kafka không chạy nên bản ghi của chúng không bao giờ gửi được):
        // đánh dấu đã gửi để lô của scheduler chỉ còn bản ghi do test này tạo
        jdbc.update("UPDATE order_event_outbox SET published_at = now() WHERE published_at IS NULL");
    }

    @Test
    void successfulSendMarksPublished() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UUID id = insertOutbox();

        scheduler.publishPendingEvents();

        assertThat(row(id).get("published_at")).isNotNull();
        assertThat(row(id).get("attempt_count")).isEqualTo(0);
    }

    @Test
    void failuresBackOffExponentiallyAndRecordErrorTypeOnly() {
        failSends();
        UUID id = insertOutbox();

        scheduler.publishPendingEvents();
        assertThat(row(id)).containsEntry("attempt_count", 1).containsEntry("last_error", "TimeoutException");
        assertThat(delaySeconds(id)).isBetween(3.5, 6.0);    // 5s × 2^0

        makeDue(id);
        scheduler.publishPendingEvents();
        assertThat(row(id).get("attempt_count")).isEqualTo(2);
        assertThat(delaySeconds(id)).isBetween(8.5, 11.0);   // 5s × 2^1
        assertThat(row(id).get("dead_at")).isNull();
    }

    @Test
    void backoffIsCappedAtMaxBackoff() {
        failSends();
        UUID id = insertOutbox();
        jdbc.update("UPDATE order_event_outbox SET attempt_count = 8 WHERE id = ?", id);

        scheduler.publishPendingEvents();

        // 5s × 2^8 = 1280s nhưng trần là 300s
        assertThat(delaySeconds(id)).isBetween(295.0, 301.0);
    }

    @Test
    void lastAllowedFailureDeadLettersAndStopsRetrying() {
        failSends();
        UUID id = insertOutbox();
        jdbc.update("UPDATE order_event_outbox SET attempt_count = 9 WHERE id = ?", id);

        scheduler.publishPendingEvents();
        assertThat(row(id).get("attempt_count")).isEqualTo(10);
        assertThat(row(id).get("dead_at")).isNotNull();
        assertThat(meterRegistry.get("outbox.dead").tag("outbox", "order_event_outbox").gauge().value())
                .isGreaterThanOrEqualTo(1.0);

        clearInvocations(kafkaTemplate);
        makeDue(id);
        scheduler.publishPendingEvents();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void batchStopsAtFirstFailure() {
        failSends();
        UUID first = insertOutbox();
        UUID second = insertOutbox();

        scheduler.publishPendingEvents();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
        assertThat(row(first).get("attempt_count")).isEqualTo(1);
        assertThat(row(second).get("attempt_count")).isEqualTo(0); // không bị tính lượt oan
        assertThat(meterRegistry.get("outbox.pending").tag("outbox", "order_event_outbox").gauge().value())
                .isGreaterThanOrEqualTo(2.0);
    }

    private void failSends() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down", new TimeoutException())));
    }

    private UUID insertOutbox() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO order_event_outbox (id, order_id, buyer_id, event_id, item_count, quantity, " +
                        "occurred_at, created_at) VALUES (?, ?, ?, ?, 1, 1, ?, clock_timestamp())",
                id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(Instant.now()));
        return id;
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE order_event_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
    }

    private double delaySeconds(UUID id) {
        return jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (next_attempt_at - now())) FROM order_event_outbox WHERE id = ?",
                Double.class, id);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM order_event_outbox WHERE id = ?", id);
    }
}
