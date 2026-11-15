package com.dev.order_service.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Ghi lần đầu một thiết bị xem tình trạng vé của event (OR07) để bot-detection biết người mua có xem
 * trang trước khi thêm giỏ hay không (D38). Best-effort: Redis lỗi không làm hỏng availability/thêm giỏ.
 */
@Component
@Slf4j
public class DeviceViewTracker {

    private static final String KEY_PREFIX = "order:device-view:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public DeviceViewTracker(
            StringRedisTemplate redisTemplate,
            @Value("${app.order.device-view-ttl:PT2H}") Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    public void recordView(String deviceHash, UUID eventId, Instant now) {
        if (deviceHash == null) {
            return;
        }
        try {
            // NX: trang sơ đồ ghế refetch availability mỗi 15 giây, chỉ lần xem đầu có ý nghĩa
            redisTemplate.opsForValue().setIfAbsent(key(deviceHash, eventId), Long.toString(now.toEpochMilli()), ttl);
        } catch (RuntimeException ex) {
            log.warn("Không ghi được lượt xem event: {}", ex.getClass().getSimpleName());
        }
    }

    public Instant firstViewAt(String deviceHash, UUID eventId) {
        if (deviceHash == null) {
            return null;
        }
        try {
            String value = redisTemplate.opsForValue().get(key(deviceHash, eventId));
            return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
        } catch (RuntimeException ex) {
            log.warn("Không đọc được lượt xem event: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private static String key(String deviceHash, UUID eventId) {
        return KEY_PREFIX + deviceHash + ":" + eventId;
    }
}
