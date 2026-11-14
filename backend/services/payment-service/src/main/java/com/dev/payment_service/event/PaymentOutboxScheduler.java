package com.dev.payment_service.event;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gửi event từ payment_event_outbox lên Kafka. Cùng chính sách với outbox Order/Product (D25):
 * backoff lũy thừa base × 2^attempt (trần max-backoff), sau max-attempts lần thì dead-letter
 * (dead_at) + log ERROR "OUTBOX_DEAD_LETTER"; gauge outbox.pending/outbox.dead.
 */
@Component
@Slf4j
public class PaymentOutboxScheduler {

    private static final String TABLE_TAG = "payment_event_outbox";

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final int maxAttempts;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();

    public PaymentOutboxScheduler(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, Object> kafkaTemplate,
            JsonMapper jsonMapper,
            MeterRegistry meterRegistry,
            @Value("${app.payment.outbox-base-backoff-ms:5000}") long baseBackoffMs,
            @Value("${app.payment.outbox-max-backoff-ms:300000}") long maxBackoffMs,
            @Value("${app.payment.outbox-max-attempts:10}") int maxAttempts
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.maxAttempts = maxAttempts;
        Gauge.builder("outbox.pending", pending, AtomicLong::get)
                .description("Bản ghi outbox chưa gửi (chưa dead-letter)").tag("outbox", TABLE_TAG)
                .register(meterRegistry);
        Gauge.builder("outbox.dead", dead, AtomicLong::get)
                .description("Bản ghi outbox đã dead-letter, cần xử lý tay").tag("outbox", TABLE_TAG)
                .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${app.payment.outbox-initial-delay-ms:1000}",
            fixedDelayString = "${app.payment.outbox-poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxRecord> due = jdbcTemplate.query(
                "SELECT id, topic, message_key, payload FROM payment_event_outbox " +
                        "WHERE published_at IS NULL AND dead_at IS NULL AND next_attempt_at <= now() " +
                        "ORDER BY created_at LIMIT 50",
                (rs, row) -> new OutboxRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("payload")
                )
        );

        for (OutboxRecord record : due) {
            try {
                // Gửi JsonNode thay vì chuỗi: JacksonJsonSerializer sẽ ghi đúng object JSON,
                // còn String sẽ bị bọc thành chuỗi JSON có dấu ngoặc kép
                kafkaTemplate.send(record.topic(), record.key(), jsonMapper.readTree(record.payload()))
                        .get(5, TimeUnit.SECONDS);
                jdbcTemplate.update(
                        "UPDATE payment_event_outbox SET published_at = now() WHERE id = ? AND published_at IS NULL",
                        record.id()
                );
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                recordFailure(record, ex);
                // Kafka lỗi thì phần còn lại của lô gần như chắc cũng lỗi: dừng, thử lại ở lần poll sau
                break;
            }
        }
        refreshGauges();
    }

    private void recordFailure(OutboxRecord record, Exception ex) {
        String errorType = rootCause(ex).getClass().getSimpleName();
        Boolean deadLettered = jdbcTemplate.queryForObject(
                "UPDATE payment_event_outbox SET attempt_count = attempt_count + 1, last_error = ?, " +
                        "next_attempt_at = now() + (LEAST(? * power(2, attempt_count), ?) * interval '1 millisecond'), " +
                        "dead_at = CASE WHEN attempt_count + 1 >= ? THEN now() END " +
                        "WHERE id = ? AND published_at IS NULL " +
                        "RETURNING dead_at IS NOT NULL",
                Boolean.class,
                errorType, baseBackoffMs, maxBackoffMs, maxAttempts, record.id()
        );
        // Chỉ log id nội bộ, topic và loại lỗi — không log payload
        if (Boolean.TRUE.equals(deadLettered)) {
            log.error("OUTBOX_DEAD_LETTER outbox={} id={} topic={} key={} attempts={} lastError={}",
                    TABLE_TAG, record.id(), record.topic(), record.key(), maxAttempts, errorType);
        } else {
            log.warn("Không thể phát {}, outboxId={} key={} error={}; sẽ retry",
                    record.topic(), record.id(), record.key(), errorType);
        }
    }

    private void refreshGauges() {
        try {
            jdbcTemplate.query(
                    "SELECT count(*) FILTER (WHERE dead_at IS NULL) AS pending, " +
                            "count(*) FILTER (WHERE dead_at IS NOT NULL) AS dead " +
                            "FROM payment_event_outbox WHERE published_at IS NULL",
                    rs -> {
                        pending.set(rs.getLong("pending"));
                        dead.set(rs.getLong("dead"));
                    }
            );
        } catch (RuntimeException ex) {
            log.warn("Không đọc được số liệu outbox: {}", ex.getClass().getSimpleName());
        }
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private record OutboxRecord(UUID id, String topic, String key, String payload) {
    }
}
