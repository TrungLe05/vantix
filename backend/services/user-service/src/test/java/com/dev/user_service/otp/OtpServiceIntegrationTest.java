package com.dev.user_service.otp;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.user_service.AbstractUserServiceIT;
import com.dev.user_service.enums.OtpPurpose;
import com.dev.user_service.exception.UserErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OTP trên Redis thật: giới hạn 5 lượt sai, dùng một lần, cooldown 60s — cả khi request đến song song.
 * Mỗi test dùng userId ngẫu nhiên nên key không đụng nhau và tự hết hạn theo TTL.
 */
class OtpServiceIntegrationTest extends AbstractUserServiceIT {

    private static final OtpPurpose PURPOSE = OtpPurpose.FORGOT_PASSWORD;

    @Autowired
    private OtpService otpService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void correctOtpIsAcceptedOnceThenInvalid() {
        UUID userId = UUID.randomUUID();
        String otp = otpService.generate(userId, PURPOSE);

        assertThatCode(() -> otpService.verify(userId, PURPOSE, otp)).doesNotThrowAnyException();
        assertError(() -> otpService.verify(userId, PURPOSE, otp), UserErrorCode.OTP_INVALID);
    }

    @Test
    void missingOtpIsInvalid() {
        assertError(() -> otpService.verify(UUID.randomUUID(), PURPOSE, "123456"), UserErrorCode.OTP_INVALID);
    }

    @Test
    void fiveWrongAttemptsLockTheOtpEvenForTheCorrectCode() {
        UUID userId = UUID.randomUUID();
        String otp = otpService.generate(userId, PURPOSE);
        String wrong = wrongCode(otp);

        for (int i = 0; i < 5; i++) {
            assertError(() -> otpService.verify(userId, PURPOSE, wrong), UserErrorCode.OTP_INVALID);
        }
        assertError(() -> otpService.verify(userId, PURPOSE, otp), UserErrorCode.OTP_ATTEMPTS_EXCEEDED);
    }

    @Test
    void wrongAttemptKeepsOriginalTtl() {
        UUID userId = UUID.randomUUID();
        String otp = otpService.generate(userId, PURPOSE);
        String key = "otp:%s:%s".formatted(PURPOSE.name(), userId);

        assertError(() -> otpService.verify(userId, PURPOSE, wrongCode(otp)), UserErrorCode.OTP_INVALID);

        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isBetween(1L, 600L);
    }

    @Test
    void concurrentWrongGuessesAreAllCounted() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        String otp = otpService.generate(userId, PURPOSE);
        String wrong = wrongCode(otp);

        List<Outcome<Boolean>> outcomes = runConcurrently(20, () -> {
            otpService.verify(userId, PURPOSE, wrong);
            return true;
        });

        // Không mất lượt đếm: đúng 5 lần "sai mã", 15 lần còn lại đã bị khóa
        Map<UserErrorCode, Long> byCode = countErrors(outcomes);
        assertThat(byCode).containsEntry(UserErrorCode.OTP_INVALID, 5L)
                .containsEntry(UserErrorCode.OTP_ATTEMPTS_EXCEEDED, 15L);
        assertError(() -> otpService.verify(userId, PURPOSE, otp), UserErrorCode.OTP_ATTEMPTS_EXCEEDED);
    }

    @Test
    void concurrentCorrectSubmissionsSucceedExactlyOnce() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        String otp = otpService.generate(userId, PURPOSE);

        List<Outcome<Boolean>> outcomes = runConcurrently(10, () -> {
            otpService.verify(userId, PURPOSE, otp);
            return true;
        });

        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(1);
        assertThat(countErrors(outcomes)).containsOnly(Map.entry(UserErrorCode.OTP_INVALID, 9L));
    }

    @Test
    void cooldownIsAcquiredOnceAndExpiresWithin60Seconds() throws InterruptedException {
        UUID userId = UUID.randomUUID();

        List<Outcome<Boolean>> outcomes = runConcurrently(10, () -> otpService.tryStartCooldown(userId, PURPOSE));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().filter(o -> o.value()).count()).isEqualTo(1);
        assertThat(otpService.tryStartCooldown(userId, PURPOSE)).isFalse();
        Long ttl = redisTemplate.getExpire("otp:cooldown:%s:%s".formatted(PURPOSE.name(), userId));
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void cooldownIsPerPurpose() {
        UUID userId = UUID.randomUUID();
        assertThat(otpService.tryStartCooldown(userId, OtpPurpose.FORGOT_PASSWORD)).isTrue();
        assertThat(otpService.tryStartCooldown(userId, OtpPurpose.EMAIL_VERIFICATION)).isTrue();
    }

    @Test
    void regeneratingResetsAttemptsAndReplacesOldCode() {
        UUID userId = UUID.randomUUID();
        String first = otpService.generate(userId, PURPOSE);
        for (int i = 0; i < 5; i++) {
            assertError(() -> otpService.verify(userId, PURPOSE, wrongCode(first)), UserErrorCode.OTP_INVALID);
        }

        String second = otpService.generate(userId, PURPOSE);

        if (!second.equals(first)) {
            assertError(() -> otpService.verify(userId, PURPOSE, first), UserErrorCode.OTP_INVALID);
        }
        assertThatCode(() -> otpService.verify(userId, PURPOSE, second)).doesNotThrowAnyException();
    }

    private static String wrongCode(String otp) {
        return otp.equals("000000") ? "000001" : "000000";
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, UserErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static Map<UserErrorCode, Long> countErrors(List<? extends Outcome<?>> outcomes) {
        return outcomes.stream()
                .filter(o -> !o.succeeded())
                .map(o -> (UserErrorCode) ((AppException) o.error()).getErrorCode())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
