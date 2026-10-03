package com.dev.user_service.otp;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_api_response.exception.CommonErrorCode;
import com.dev.user_service.enums.OtpPurpose;
import com.dev.user_service.exception.UserErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OtpService {

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    @Value("${app.otp.hmac-secret}")
    private String hmacSecret;

    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Sinh OTP mới, đồng thời set cooldown 60s. Trả plaintext để publish Kafka — KHÔNG log giá trị này.
     */
    public String generate(UUID userId, OtpPurpose purpose) {
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hash = hmacHash(userId, purpose, otp);

        writeEntryWithTtl(buildOtpKey(userId, purpose), new OtpEntry(hash, 0), OTP_TTL.getSeconds());
        redisTemplate.opsForValue().set(buildCooldownKey(userId, purpose), "1", COOLDOWN_TTL);

        return otp;
    }

    public void verify(UUID userId, OtpPurpose purpose, String otp) {
        String key = buildOtpKey(userId, purpose);
        String raw = redisTemplate.opsForValue().get(key);

        if (raw == null) {
            throw new AppException(UserErrorCode.OTP_INVALID);
        }

        OtpEntry entry = readEntry(raw);

        if (entry.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new AppException(UserErrorCode.OTP_ATTEMPTS_EXCEEDED);
        }

        String expectedHash = hmacHash(userId, purpose, otp);
        boolean matched = MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                entry.getOtpCodeHash().getBytes(StandardCharsets.UTF_8)
        );

        if (!matched) {
            entry.setAttemptCount(entry.getAttemptCount() + 1);
            Long remainingTtl = redisTemplate.getExpire(key);
            writeEntryWithTtl(key, entry, remainingTtl != null && remainingTtl > 0 ? remainingTtl : 1);
            throw new AppException(UserErrorCode.OTP_INVALID);
        }

        redisTemplate.delete(key);
    }

    public void invalidate(UUID userId, OtpPurpose purpose) {
        redisTemplate.delete(buildOtpKey(userId, purpose));
    }

    /**
     * true nếu đang trong 60s cooldown kể từ lần gửi OTP gần nhất — dùng cho A03/A10
     */
    public boolean isInCooldown(UUID userId, OtpPurpose purpose) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildCooldownKey(userId, purpose)));
    }

    private void writeEntryWithTtl(String key, OtpEntry entry, long ttlSeconds) {
        try {
            String json = jsonMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            throw new AppException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    private OtpEntry readEntry(String raw) {
        try {
            return jsonMapper.readValue(raw, OtpEntry.class);
        } catch (Exception e) {
            throw new AppException(CommonErrorCode.INTERNAL_ERROR);
        }
    }

    private String buildOtpKey(UUID userId, OtpPurpose purpose) {
        return "otp:%s:%s".formatted(purpose.name(), userId);
    }

    private String buildCooldownKey(UUID userId, OtpPurpose purpose) {
        return "otp:cooldown:%s:%s".formatted(purpose.name(), userId);
    }

    private String hmacHash(UUID userId, OtpPurpose purpose, String otp) {
        try {
            String message = userId + ":" + purpose.name() + ":" + otp;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] result = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (Exception e) {
            throw new AppException(CommonErrorCode.INTERNAL_ERROR);
        }
    }
}
