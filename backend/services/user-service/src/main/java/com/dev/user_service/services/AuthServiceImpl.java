package com.dev.user_service.services;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_kafka.event.EmailVerificationRequestedEvent;
import com.dev.commonlib_kafka.event.ForgotPasswordRequestedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.commonlibjwt.jwt.JwtProperties;
import com.dev.commonlibjwt.jwt.JwtUtils;
import com.dev.user_service.dto.request.*;
import com.dev.user_service.dto.response.AuthResponse;
import com.dev.user_service.dto.response.LoginResponse;
import com.dev.user_service.dto.response.UserResponse;
import com.dev.user_service.entities.RefreshToken;
import com.dev.user_service.entities.User;
import com.dev.user_service.enums.OauthProvider;
import com.dev.user_service.enums.OtpPurpose;
import com.dev.user_service.enums.UserRole;
import com.dev.user_service.enums.UserStatus;
import com.dev.user_service.exception.UserErrorCode;
import com.dev.user_service.otp.OtpService;
import com.dev.user_service.repository.RefreshTokenRepository;
import com.dev.user_service.repository.UserRepository;
import com.dev.user_service.security.TokenHasher;
import com.dev.user_service.util.SecureRandomStringGenerator;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtils jwtUtils;
    private final TokenHasher tokenHasher;
    private final JwtProperties jwtProperties;
    private final SecureRandomStringGenerator randomStringGenerator;
    private final StringRedisTemplate redisTemplate;
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5L4vJfmTF7ntCJ3zSjkTKZjPmQXgS";


    @Override
    public UserResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        // Pre-check: chặn sớm trường hợp thông thường, KHÔNG phải cơ chế chống race condition cuối cùng
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new AppException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // Băm mật khẩu ngoài transaction — CPU-bound, không cần giữ transaction mở trong lúc BCrypt chạy
        String passwordHash = passwordEncoder.encode(request.getPassword());

        User savedUser;
        try {
            savedUser = transactionTemplate.execute(status -> {
                User user = User.builder()
                        .email(email)
                        .passwordHash(passwordHash)
                        .fullName(request.getFullName().trim())
                        .role(com.dev.user_service.enums.UserRole.BUYER)
                        .status(UserStatus.PENDING_VERIFICATION)
                        .build();
                return userRepository.save(user);
            });
        } catch (DataIntegrityViolationException e) {
            // Lớp bảo vệ cuối cùng: 2 request đăng ký cùng email đến gần như đồng thời,
            // cả 2 đều qua được pre-check ở trên, chỉ 1 request insert thành công nhờ partial unique index
            throw new AppException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // Transaction DB đã commit tại đây — an toàn để sinh OTP + publish Kafka
        String otpCode = otpService.generate(savedUser.getId(), OtpPurpose.EMAIL_VERIFICATION);

        kafkaTemplate.send(
                KafkaTopics.USER_EMAIL_VERIFICATION_REQUESTED,
                savedUser.getId().toString(), // key = userId: đảm bảo mọi event cùng user vào cùng partition, giữ thứ tự
                new EmailVerificationRequestedEvent(
                        savedUser.getId(),
                        savedUser.getEmail(),
                        savedUser.getFullName(),
                        otpCode
                )
        ).whenComplete((result, ex) -> {
            if (ex != null) {
                // Không ném lỗi ngược lại người dùng — user đã đăng ký thành công, DB đã commit.
                // Mất event ở đây là giới hạn đã chấp nhận (mục 1.6), chỉ log để biết mà điều tra,
                // người dùng vẫn có đường thoát qua API resend-verification (A03)
                log.error("Publish user.email-verification-requested thất bại, userId={}", savedUser.getId(), ex);
            }
        });

        return UserResponse.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .fullName(savedUser.getFullName())
                .role(savedUser.getRole())
                .status(savedUser.getStatus())
                .oauthProvider(savedUser.getOauthProvider())
                .mustChangePassword(savedUser.isMustChangePassword())
                .build();
    }

    @Override
    public void verifyEmail(VerifyEmailRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                // Không tồn tại → dùng CHUNG lỗi OTP_INVALID với "OTP sai", tránh lộ email có tồn tại hay không
                .orElseThrow(() -> new AppException(UserErrorCode.OTP_INVALID));

        if (user.getStatus() == UserStatus.ACTIVE) {
            throw new AppException(UserErrorCode.EMAIL_ALREADY_VERIFIED);
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AppException(UserErrorCode.ACCOUNT_LOCKED);
        }

        // Ném AppException(OTP_INVALID) hoặc AppException(OTP_ATTEMPTS_EXCEEDED) nếu sai — xem OtpService.verify()
        otpService.verify(user.getId(), OtpPurpose.EMAIL_VERIFICATION, request.getOtp());

        // OTP đúng — OtpService.verify() đã tự xóa key khỏi Redis (dùng một lần), chỉ cần cập nhật status
        transactionTemplate.executeWithoutResult(status -> {
            user.setStatus(UserStatus.ACTIVE);
            userRepository.save(user);
        });
    }

    @Override
    public void resendVerification(ResendVerificationRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        userRepository.findByEmailAndDeletedAtIsNull(email)
                .filter(user -> user.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresent(user -> {
                    // Cooldown 60s: nếu đang trong cooldown, coi như đã "gửi" (im lặng), không sinh OTP mới
                    if (otpService.isInCooldown(user.getId(), OtpPurpose.EMAIL_VERIFICATION)) {
                        return;
                    }

                    // generate() tự ghi đè OTP cũ (nếu còn) và set lại cooldown — không cần gọi invalidate() riêng
                    String otpCode = otpService.generate(user.getId(), OtpPurpose.EMAIL_VERIFICATION);

                    kafkaTemplate.send(
                            KafkaTopics.USER_EMAIL_VERIFICATION_REQUESTED,
                            user.getId().toString(),
                            new EmailVerificationRequestedEvent(
                                    user.getId(), user.getEmail(), user.getFullName(), otpCode
                            )
                    ).whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Publish user.email-verification-requested (resend) thất bại, userId={}", user.getId(), ex);
                        }
                    });
                });

        // Không có nhánh else, không ném exception nào — luôn trả về bình thường dù email không tồn tại/đã ACTIVE/đang cooldown
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElse(null);

        // Chống user enumeration qua timing: luôn chạy đúng 1 lần passwordEncoder.matches(),
        // dù user có tồn tại hay không / có password_hash hay không (tài khoản chỉ Google)
        String hashToCheck = (user != null && user.getPasswordHash() != null)
                ? user.getPasswordHash()
                : DUMMY_PASSWORD_HASH;
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (user == null || user.getPasswordHash() == null || !passwordMatches) {
            throw new AppException(UserErrorCode.INVALID_CREDENTIALS);
        }

        // CHỈ kiểm tra status SAU KHI mật khẩu đã đúng — tránh lộ trạng thái tài khoản cho kẻ dò email
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            throw new AppException(UserErrorCode.ACCOUNT_NOT_VERIFIED);
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AppException(UserErrorCode.ACCOUNT_LOCKED);
        }

        return issueTokens(user, UUID.randomUUID());
    }

    @Override
    public String handleGoogleLoginSuccess(String sub, String email, boolean emailVerified, String fullName) {
        if (!emailVerified) {
            throw new AppException(UserErrorCode.OAUTH_EMAIL_NOT_VERIFIED);
        }

        User user = resolveGoogleUser(sub, email, fullName);

        String oneTimeCode = randomStringGenerator.generate(32);
        redisTemplate.opsForValue().set(
                "oauth:code:" + oneTimeCode, user.getId().toString(), Duration.ofSeconds(60)
        );
        return oneTimeCode;
    }

    @Override
    public LoginResponse exchangeOAuthCode(OAuth2ExchangeRequest request) {
        String key = "oauth:code:" + request.getCode();

        // getAndDelete: đọc + xóa NGUYÊN TỬ (Redis GETDEL) — code chỉ dùng được đúng 1 lần,
        // request thứ 2 dùng lại cùng code sẽ nhận null dù đến gần như cùng lúc
        String userIdRaw = redisTemplate.opsForValue().getAndDelete(key);

        if (userIdRaw == null) {
            throw new AppException(UserErrorCode.OAUTH_EXCHANGE_CODE_INVALID);
        }

        User user = userRepository.findById(UUID.fromString(userIdRaw))
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_FOUND));

        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AppException(UserErrorCode.ACCOUNT_LOCKED);
        }

        return issueTokens(user, UUID.randomUUID());
    }

    @Override
    public LoginResponse refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AppException(UserErrorCode.REFRESH_TOKEN_INVALID);
        }

        try {
            jwtUtils.verifyRefreshToken(rawRefreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new AppException(UserErrorCode.REFRESH_TOKEN_INVALID);
        }

        String tokenHash = tokenHasher.hash(rawRefreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AppException(UserErrorCode.REFRESH_TOKEN_INVALID));

        Instant now = Instant.now();

        if (token.getRevokedAt() != null) {
            // Ghi PHẢI commit ngay tại đây — không để exception ném ngay sau đó rollback mất
            transactionTemplate.executeWithoutResult(status ->
                    refreshTokenRepository.revokeAllByFamilyId(token.getFamilyId(), now));
            throw new AppException(UserErrorCode.REFRESH_TOKEN_REUSED);
        }

        User user = userRepository.findById(token.getUserId())
                .filter(u -> u.getDeletedAt() == null)
                .orElse(null);

        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            UserStatus status = user != null ? user.getStatus() : null;
            transactionTemplate.executeWithoutResult(s ->
                    refreshTokenRepository.revokeAllByFamilyId(token.getFamilyId(), now));
            if (status == UserStatus.LOCKED) {
                throw new AppException(UserErrorCode.ACCOUNT_LOCKED);
            }
            throw new AppException(UserErrorCode.REFRESH_TOKEN_INVALID);
        }

        Boolean revoked = transactionTemplate.execute(s -> refreshTokenRepository.revokeIfActive(token.getId(), now) > 0);
        if (!Boolean.TRUE.equals(revoked)) {
            transactionTemplate.executeWithoutResult(s ->
                    refreshTokenRepository.revokeAllByFamilyId(token.getFamilyId(), now));
            throw new AppException(UserErrorCode.REFRESH_TOKEN_REUSED);
        }

        return issueTokens(user, token.getFamilyId());
    }

    @Override
    public void logout(String rawRefreshToken, UUID userId) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return; // không có gì để thu hồi — logout vẫn coi như thành công
        }
        String tokenHash = tokenHasher.hash(rawRefreshToken);

        refreshTokenRepository.findByTokenHash(tokenHash)
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> transactionTemplate.executeWithoutResult(status ->
                        refreshTokenRepository.revokeAllByFamilyId(token.getFamilyId(), Instant.now())
                ));
        // Không tìm thấy, hoặc token thuộc user khác -> không làm gì, vẫn trả 200 (idempotent,
        // không lộ thông tin về token của người khác — đúng lưu ý bảo mật ở API design A09)
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        userRepository.findByEmailAndDeletedAtIsNull(email)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(user -> {
                    if (otpService.isInCooldown(user.getId(), OtpPurpose.FORGOT_PASSWORD)) {
                        return;
                    }

                    String otpCode = otpService.generate(user.getId(), OtpPurpose.FORGOT_PASSWORD);

                    kafkaTemplate.send(
                            KafkaTopics.USER_FORGOT_PASSWORD_REQUESTED,
                            user.getId().toString(),
                            new ForgotPasswordRequestedEvent(
                                    user.getId(), user.getEmail(), user.getFullName(), otpCode
                            )
                    ).whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Publish user.forgot-password-requested thất bại, userId={}", user.getId(), ex);
                        }
                    });
                });

        // Luôn trả về bình thường — không ném exception, không phân biệt email tồn tại hay không
    }

    @Override
    public void resetPassword(ResetPasswordRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmailAndDeletedAtIsNull(email)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                // Không tồn tại / không ACTIVE -> dùng CHUNG lỗi OTP_INVALID, không lộ trạng thái tài khoản
                .orElseThrow(() -> new AppException(UserErrorCode.OTP_INVALID));

        // Ném AppException(OTP_INVALID) hoặc AppException(OTP_ATTEMPTS_EXCEEDED) nếu sai
        otpService.verify(user.getId(), OtpPurpose.FORGOT_PASSWORD, request.getOtp());

        String newPasswordHash = passwordEncoder.encode(request.getNewPassword());
        Instant now = Instant.now();

        transactionTemplate.executeWithoutResult(status -> {
            user.setPasswordHash(newPasswordHash);
            user.setMustChangePassword(false);
            userRepository.save(user);
            refreshTokenRepository.revokeAllByUserId(user.getId(), now);
        });
    }

    @Override
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_FOUND));

        if (user.getPasswordHash() == null) {
            throw new AppException(UserErrorCode.PASSWORD_NOT_SET);
        }

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new AppException(UserErrorCode.PASSWORD_INCORRECT);
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new AppException(UserErrorCode.NEW_PASSWORD_SAME_AS_OLD);
        }

        String newPasswordHash = passwordEncoder.encode(request.getNewPassword());
        Instant now = Instant.now();

        transactionTemplate.executeWithoutResult(status -> {
            user.setPasswordHash(newPasswordHash);
            user.setMustChangePassword(false);
            userRepository.save(user);
            refreshTokenRepository.revokeAllByUserId(user.getId(), now);
        });
    }


    private LoginResponse issueTokens(User user, UUID familyId) {

        String rawRefreshToken = jwtUtils.generateRefreshToken(
                user.getId().toString(), user.getRole().name(), user.getEmail(), user.isMustChangePassword()
        );

        RefreshToken refreshToken = RefreshToken.builder()
                .familyId(familyId)
                .userId(user.getId())
                .tokenHash(tokenHasher.hash(rawRefreshToken))
                .build();
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtUtils.generateAccessToken(
                user.getId().toString(), user.getRole().name(), user.getEmail(), user.isMustChangePassword()
        );

        return buildResponse(user, accessToken, rawRefreshToken);
    }

    private User resolveGoogleUser(String sub, String email, String fullName) {
        var byOauthId = userRepository.findByOauthProviderAndOauthIdAndDeletedAtIsNull(OauthProvider.GOOGLE, sub);
        if (byOauthId.isPresent()) {
            User user = byOauthId.get();
            if (user.getStatus() == UserStatus.LOCKED) throw new AppException(UserErrorCode.ACCOUNT_LOCKED);
            return user;
        }

        String normalizedEmail = email.trim().toLowerCase();
        var byEmail = userRepository.findByEmailAndDeletedAtIsNull(normalizedEmail);

        if (byEmail.isPresent()) {
            User user = byEmail.get();
            if (user.getOauthId() != null && !user.getOauthId().equals(sub)) {
                throw new AppException(UserErrorCode.OAUTH_ACCOUNT_CONFLICT);
            }
            if (user.getStatus() == UserStatus.LOCKED) throw new AppException(UserErrorCode.ACCOUNT_LOCKED);

            return transactionTemplate.execute(status -> {
                if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
                    user.setStatus(UserStatus.ACTIVE);
                    user.setPasswordHash(null); // chống pre-hijacking
                }
                user.setOauthProvider(OauthProvider.GOOGLE);
                user.setOauthId(sub);
                return userRepository.save(user);
            });
        }

        return transactionTemplate.execute(status -> userRepository.save(
                User.builder()
                        .email(normalizedEmail)
                        .fullName(fullName != null ? fullName : normalizedEmail.split("@")[0])
                        .role(UserRole.BUYER)
                        .status(UserStatus.ACTIVE)
                        .oauthProvider(OauthProvider.GOOGLE)
                        .oauthId(sub)
                        .build()
        ));
    }


    // helper method
    private LoginResponse buildResponse(User user, String accessToken, String rawRefreshToken){
        return LoginResponse.builder()
                .auth(AuthResponse.builder()
                        .accessToken(accessToken)
                        .refreshToken(rawRefreshToken)
                        .tokenType("Bearer")
                        .expiresIn(jwtProperties.getExpiration().toSeconds())
                        .build())
                .user(UserResponse.builder()
                        .id(user.getId())
                        .role(user.getRole())
                        .email(user.getEmail())
                        .oauthProvider(user.getOauthProvider())
                        .status(user.getStatus())
                        .fullName(user.getFullName())
                        .mustChangePassword(user.isMustChangePassword())
                        .build())
                .build();
    }
}