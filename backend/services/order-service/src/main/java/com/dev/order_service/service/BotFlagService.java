package com.dev.order_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.dto.response.BotFlagResponse;
import com.dev.order_service.dto.response.PageResponse;
import com.dev.order_service.entity.BotFlag;
import com.dev.order_service.enums.BotFlagStatus;
import com.dev.order_service.enums.OrderErrorCode;
import com.dev.order_service.event.BotScoreEvaluatedEvent;
import com.dev.order_service.repository.BotFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phản ứng với điểm rủi ro của bot-detection (D41, D46): chặn thêm giỏ theo buyer hoặc thiết bị, không đụng
 * tới hold đang có. Chỉ {@code HIGH} tạo flag; mức khác (kể cả UNSCORED khi chưa có model, D42) bị bỏ qua.
 * HIGH kèm luật tay → ACTIVE (chặn ngay); HIGH không kèm luật → WATCH, tới lần HIGH thứ hai mới ACTIVE.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotFlagService {

    private static final TypeReference<Map<String, Object>> FEATURE_MAP = new TypeReference<>() {
    };

    private final BotFlagRepository botFlagRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public void ensureCanAddToCart(UUID buyerId, String deviceHash) {
        boolean blocked = botFlagRepository.existsByStatusAndBuyerId(BotFlagStatus.ACTIVE, buyerId)
                || (deviceHash != null
                && botFlagRepository.existsByStatusAndDeviceHash(BotFlagStatus.ACTIVE, deviceHash));
        if (blocked) {
            throw new AppException(OrderErrorCode.BOT_REVIEW_REQUIRED);
        }
    }

    /**
     * Gộp vào dòng đang mở (WATCH/ACTIVE) của buyer (giữ điểm cao nhất, D46: đã có dòng mở nghĩa là HIGH lần hai → ACTIVE). Bản tin của lần thêm giỏ xảy ra trước lần ADMIN
     * gỡ chặn gần nhất bị bỏ qua: Kafka phát lại không được khóa lại người vừa được gỡ.
     *
     * @return true nếu đã tạo/cập nhật flag
     */
    @Transactional
    public boolean recordEvaluation(BotScoreEvaluatedEvent event) {
        if (!event.isHighRisk() || event.buyerId() == null) {
            return false;
        }
        Instant flaggedAt = event.occurredAt() != null ? event.occurredAt()
                : event.evaluatedAt() != null ? event.evaluatedAt() : Instant.now();
        String features = jsonMapper.writeValueAsString(event.features() == null ? Map.of() : event.features());
        String modelVersion = event.modelVersion() == null ? "unknown" : event.modelVersion();
        Timestamp at = Timestamp.from(flaggedAt);
        String initialStatus = event.ruleMatchedOrFalse() ? "ACTIVE" : "WATCH";
        int changed = jdbcTemplate.update(
                "INSERT INTO bot_flags (buyer_id, device_hash, order_id, event_id, score, model_version, features, " +
                        "status, hit_count, first_flagged_at, last_flagged_at) " +
                        "SELECT ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ? " +
                        "WHERE NOT EXISTS (SELECT 1 FROM bot_flags WHERE buyer_id = ? AND status = 'CLEARED' " +
                        "AND cleared_at >= ?) " +
                        "ON CONFLICT (buyer_id) WHERE status IN ('WATCH', 'ACTIVE') DO UPDATE SET " +
                        "hit_count = bot_flags.hit_count + 1, " +
                        "status = 'ACTIVE', " +
                        "last_flagged_at = GREATEST(bot_flags.last_flagged_at, EXCLUDED.last_flagged_at), " +
                        "device_hash = COALESCE(bot_flags.device_hash, EXCLUDED.device_hash), " +
                        "order_id = CASE WHEN EXCLUDED.score > bot_flags.score THEN EXCLUDED.order_id ELSE bot_flags.order_id END, " +
                        "event_id = CASE WHEN EXCLUDED.score > bot_flags.score THEN EXCLUDED.event_id ELSE bot_flags.event_id END, " +
                        "features = CASE WHEN EXCLUDED.score > bot_flags.score THEN EXCLUDED.features ELSE bot_flags.features END, " +
                        "model_version = CASE WHEN EXCLUDED.score > bot_flags.score THEN EXCLUDED.model_version ELSE bot_flags.model_version END, " +
                        "score = GREATEST(bot_flags.score, EXCLUDED.score), " +
                        "updated_at = now()",
                event.buyerId(), event.deviceHash(), event.orderId(), event.eventId(), event.anomalyScore(),
                modelVersion, features, initialStatus, at, at, event.buyerId(), at
        );
        if (changed > 0) {
            // Không log deviceHash/feature: chỉ id nội bộ và điểm
            log.info("BOT_FLAGGED buyerId={} orderId={} score={} model={} ruleMatched={}",
                    event.buyerId(), event.orderId(), event.anomalyScore(), modelVersion, event.ruleMatchedOrFalse());
        }
        return changed > 0;
    }

    @Transactional(readOnly = true)
    public PageResponse<BotFlagResponse> list(BotFlagStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<BotFlag> result = status == null
                ? botFlagRepository.findAllByOrderByLastFlaggedAtDescIdAsc(pageable)
                : botFlagRepository.findByStatusOrderByLastFlaggedAtDescIdAsc(status, pageable);
        return PageResponse.<BotFlagResponse>builder()
                .content(result.getContent().stream().map(this::toResponse).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    /** Idempotent: gỡ lại flag đã CLEARED trả về đúng trạng thái cũ, không ghi đè người gỡ trước. */
    @Transactional
    public BotFlagResponse clear(UUID adminId, UUID flagId) {
        BotFlag flag = botFlagRepository.findByIdForUpdate(flagId)
                .orElseThrow(() -> new AppException(OrderErrorCode.BOT_FLAG_NOT_FOUND));
        if (flag.getStatus() != BotFlagStatus.CLEARED) {
            flag.setStatus(BotFlagStatus.CLEARED);
            flag.setClearedAt(Instant.now());
            flag.setClearedBy(adminId);
            botFlagRepository.saveAndFlush(flag);
            log.info("BOT_FLAG_CLEARED flagId={} buyerId={} by={}", flag.getId(), flag.getBuyerId(), adminId);
        }
        return toResponse(flag);
    }

    private BotFlagResponse toResponse(BotFlag flag) {
        return BotFlagResponse.builder()
                .id(flag.getId())
                .buyerId(flag.getBuyerId())
                .deviceHash(flag.getDeviceHash())
                .orderId(flag.getOrderId())
                .eventId(flag.getEventId())
                .score(flag.getScore())
                .modelVersion(flag.getModelVersion())
                .features(readFeatures(flag.getFeatures()))
                .status(flag.getStatus().name())
                .hitCount(flag.getHitCount())
                .firstFlaggedAt(flag.getFirstFlaggedAt())
                .lastFlaggedAt(flag.getLastFlaggedAt())
                .clearedAt(flag.getClearedAt())
                .clearedBy(flag.getClearedBy())
                .build();
    }

    private Map<String, Object> readFeatures(String json) {
        try {
            return jsonMapper.readValue(json, FEATURE_MAP);
        } catch (RuntimeException ex) {
            log.warn("features của bot flag không phải JSON hợp lệ: {}", ex.getClass().getSimpleName());
            return Map.of();
        }
    }
}
