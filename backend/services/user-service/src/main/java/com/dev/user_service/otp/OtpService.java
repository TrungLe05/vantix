package com.dev.user_service.otp;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_api_response.exception.CommonErrorCode;
import com.dev.user_service.enums.OtpPurpose;
import com.dev.user_service.exception.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
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

    private static final long VERIFY_INVALID = 0;
    private static final long VERIFY_OK = 1;
    private static final long VERIFY_ATTEMPTS_EXCEEDED = -1;

    // KEYS[1] = key OTP; ARGV[1] = hash HMAC của mã người dùng nhập; ARGV[2] = số lượt sai tối đa.
    // Giá trị là JSON OtpEntry {otpCodeHash, attemptCount} do generate() ghi; KEEPTTL giữ hạn 10 phút ban đầu.
    // So sánh chuỗi trong Lua không constant-time, nhưng hai vế đều là HMAC phía server (kẻ tấn công
    // không biết secret) nên rò rỉ thời gian không giúp đoán được OTP.
    private static final RedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then return %d end
            local entry = cjson.decode(raw)
            local attempts = tonumber(entry['attemptCount']) or 0
            if attempts >= tonumber(ARGV[2]) then return %d end
            if entry['otpCodeHash'] == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return %d
            end
            entry['attemptCount'] = attempts + 1
            redis.call('SET', KEYS[1], cjson.encode(entry), 'KEEPTTL')
            return %d
            """.formatted(VERIFY_INVALID, VERIFY_ATTEMPTS_EXCEEDED, VERIFY_OK, VERIFY_INVALID), Long.class);

    /**
     * Sinh OTP mới (ghi đè mã cũ), đồng thời set lại cooldown 60s (register không qua tryStartCooldown).
     * Trả plaintext để publish Kafka — KHÔNG log giá trị này.
     */
    public String generate(UUID userId, OtpPurpose purpose) {
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hash = hmacHash(userId, purpose, otp);

        writeEntryWithTtl(buildOtpKey(userId, purpose), new OtpEntry(hash, 0), OTP_TTL.getSeconds());
        redisTemplate.opsForValue().set(buildCooldownKey(userId, purpose), "1", COOLDOWN_TTL);

        return otp;
    }

    public void verify(UUID userId, OtpPurpose purpose, String otp) {
        // Đọc-so khớp-tăng lượt sai-xóa chạy trong MỘT script Lua (nguyên tử phía Redis):
        // nếu làm ở Java, N request sai song song cùng đọc attemptCount cũ nên chỉ ghi được +1
        // (vượt giới hạn 5 lượt), và hai request đúng song song đều qua được trước khi kịp xóa.
        Long result = redisTemplate.execute(VERIFY_SCRIPT,
                List.of(buildOtpKey(userId, purpose)),
                hmacHash(userId, purpose, otp), String.valueOf(MAX_ATTEMPTS));

        if (result != null && result == VERIFY_OK) {
            return;
        }
        if (result != null && result == VERIFY_ATTEMPTS_EXCEEDED) {
            throw new AppException(UserErrorCode.OTP_ATTEMPTS_EXCEEDED);
        }
        throw new AppException(UserErrorCode.OTP_INVALID);
    }

    public void invalidate(UUID userId, OtpPurpose purpose) {
        redisTemplate.delete(buildOtpKey(userId, purpose));
    }

    /**
     * Chiếm cooldown 60s cho lần gửi OTP (A03/A10). Trả false nếu đang trong cooldown.
     * SET NX nguyên tử: hai request gửi lại song song chỉ một request được sinh/gửi OTP mới.
     */
    public boolean tryStartCooldown(UUID userId, OtpPurpose purpose) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(buildCooldownKey(userId, purpose), "1", COOLDOWN_TTL));
    }

    private void writeEntryWithTtl(String key, OtpEntry entry, long ttlSeconds) {
        try {
            String json = jsonMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
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
