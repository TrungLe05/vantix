package com.dev.payment_service.event;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Ghi event vào payment_event_outbox. Phải gọi trong transaction nghiệp vụ để event và thay đổi
 * trạng thái cùng commit/rollback (D11); {@link PaymentOutboxScheduler} gửi lên Kafka sau.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public void record(String topic, UUID orderId, Object event) {
        jdbcTemplate.update(
                "INSERT INTO payment_event_outbox (topic, message_key, payload) VALUES (?, ?, ?)",
                topic, orderId.toString(), jsonMapper.writeValueAsString(event)
        );
    }
}
