package com.dev.order_service.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Payload {@code bot_score.evaluated} do bot-detection (Python) phát. Chỉ order-service đọc nên không
 * đặt trong commonlib-kafka (rule of 3). Bỏ qua field lạ để bot-detection thêm field không làm vỡ consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotScoreEvaluatedEvent(
        UUID evaluationId,
        UUID orderId,
        UUID buyerId,
        UUID eventId,
        String deviceHash,
        BigDecimal anomalyScore,
        String riskLevel,
        String modelVersion,
        // D46: luật tay (dồn dập/nhiều tài khoản trên thiết bị) cũng đúng; null với bản tin cũ = false
        Boolean ruleMatched,
        Map<String, Object> features,
        Instant occurredAt,
        Instant evaluatedAt
) {
    public boolean ruleMatchedOrFalse() {
        return Boolean.TRUE.equals(ruleMatched);
    }

    public boolean isHighRisk() {
        return "HIGH".equals(riskLevel) && anomalyScore != null;
    }
}
