package com.dev.order_service.device;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Băm device ID do frontend sinh (D37). Chỉ hash rời order-service (Kafka, bot_flags): HMAC có
 * secret nên bên nhận không dò ngược được ID thô, kể cả khi biết định dạng UUID.
 */
@Component
@Slf4j
public class DeviceHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public DeviceHasher(@Value("${app.order.device-hash-secret:}") String secret) {
        byte[] material;
        if (StringUtils.hasText(secret)) {
            material = secret.getBytes(StandardCharsets.UTF_8);
        } else {
            // Không chặn dev khởi động khi thiếu secret; đổi lại hash đổi sau mỗi lần restart nên
            // feature "nhiều tài khoản trên một thiết bị" bị reset
            material = new byte[32];
            new SecureRandom().nextBytes(material);
            log.warn("DEVICE_HASH_SECRET chưa cấu hình: dùng secret ngẫu nhiên, deviceHash không ổn định qua restart");
        }
        this.key = new SecretKeySpec(material, ALGORITHM);
    }

    /** Header không phải UUID → empty: client lạ/tự viết không làm request lỗi, chỉ mất tín hiệu thiết bị. */
    public Optional<String> hash(String rawDeviceId) {
        if (!StringUtils.hasText(rawDeviceId) || rawDeviceId.length() > 64) {
            return Optional.empty();
        }
        String normalized;
        try {
            normalized = UUID.fromString(rawDeviceId.trim()).toString().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return Optional.of(HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 không khả dụng", ex);
        }
    }
}
