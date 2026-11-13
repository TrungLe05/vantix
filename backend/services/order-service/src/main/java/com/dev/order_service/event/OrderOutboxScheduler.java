package com.dev.order_service.event;

import com.dev.commonlib_kafka.topic.KafkaTopics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gửi cart.item_added từ order_event_outbox (transactional outbox, D11).
 * Lỗi gửi: backoff lũy thừa base × 2^attempt (trần max-backoff); sau max-attempts lần thì
 * dead-letter (dead_at) + log ERROR "OUTBOX_DEAD_LETTER" và không retry nữa. Gauge
 * outbox.pending/outbox.dead làm tín hiệu alert (D25).
 */
@Component
@Slf4j
public class OrderOutboxScheduler {

    private static final String TABLE_TAG = "order_event_outbox";

    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final int maxAttempts;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();

    public OrderOutboxScheduler(
            JdbcTemplate jdbcTemplate,
            KafkaTemplate<String, Object> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${app.order.outbox-base-backoff-ms:5000}") long baseBackoffMs,
            @Value("${app.order.outbox-max-backoff-ms:300000}") long maxBackoffMs,
            @Value("${app.order.outbox-max-attempts:10}") int maxAttempts
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaTemplate = kafkaTemplate;
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

    @Scheduled(initialDelayString = "${app.order.outbox-initial-delay-ms:1000}",
            fixedDelayString = "${app.order.outbox-poll-interval-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxRecord> due = jdbcTemplate.query(
                "SELECT id, order_id, buyer_id, event_id, item_count, quantity, occurred_at " +
                        "FROM order_event_outbox " +
                        "WHERE published_at IS NULL AND dead_at IS NULL AND next_attempt_at <= now() " +
                        "ORDER BY created_at LIMIT 50",
                (rs, row) -> new OutboxRecord(
                        rs.getObject("id", UUID.class),
                        new CartItemAddedEvent(
                                rs.getObject("order_id", UUID.class),
                                rs.getObject("buyer_id", UUID.class),
                                rs.getObject("event_id", UUID.class),
                                rs.getInt("item_count"),
                                rs.getInt("quantity"),
                                rs.getTimestamp("occurred_at").toInstant()
                        )
                )
        );

        for (OutboxRecord record : due) {
            try {
                kafkaTemplate.send(
                        KafkaTopics.ORDER_CART_ITEM_ADDED,
                        record.event().orderId().toString(),
                        record.event()
                ).get(5, TimeUnit.SECONDS);
                jdbcTemplate.update(
                        "UPDATE order_event_outbox SET published_at = now() WHERE id = ? AND published_at IS NULL",
                        record.id()
                );
            } catch (Exception ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                recordFailure(record, ex);
                // Kafka lỗi thì các bản ghi còn lại trong lô gần như chắc cũng lỗi: dừng lô để không
                // chặn scheduler N lần liên tiếp; phần còn lại thử ở lần poll sau, không bị tính lượt
                break;
            }
        }
        refreshGauges();
    }

    private void recordFailure(OutboxRecord record, Exception ex) {
        String errorType = rootCause(ex).getClass().getSimpleName();
        Boolean deadLettered = jdbcTemplate.queryForObject(
                "UPDATE order_event_outbox SET attempt_count = attempt_count + 1, last_error = ?, " +
                        "next_attempt_at = now() + (LEAST(? * power(2, attempt_count), ?) * interval '1 millisecond'), " +
                        "dead_at = CASE WHEN attempt_count + 1 >= ? THEN now() END " +
                        "WHERE id = ? AND published_at IS NULL " +
                        "RETURNING dead_at IS NOT NULL",
                Boolean.class,
                errorType, baseBackoffMs, maxBackoffMs, maxAttempts, record.id()
        );
        // Chỉ log id nội bộ và loại lỗi; payload không có PII nhưng không cần đưa vào log
        if (Boolean.TRUE.equals(deadLettered)) {
            log.error("OUTBOX_DEAD_LETTER outbox={} id={} orderId={} attempts={} lastError={}",
                    TABLE_TAG, record.id(), record.event().orderId(), maxAttempts, errorType);
        } else {
            log.warn("Không thể phát cart.item_added, outboxId={} orderId={} error={}; sẽ retry",
                    record.id(), record.event().orderId(), errorType);
        }
    }

    private void refreshGauges() {
        try {
            jdbcTemplate.query(
                    "SELECT count(*) FILTER (WHERE dead_at IS NULL) AS pending, " +
                            "count(*) FILTER (WHERE dead_at IS NOT NULL) AS dead " +
                            "FROM order_event_outbox WHERE published_at IS NULL",
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

    private record OutboxRecord(UUID id, CartItemAddedEvent event) {
    }
}
