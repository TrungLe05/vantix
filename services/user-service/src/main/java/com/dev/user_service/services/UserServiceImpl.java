package com.dev.user_service.services;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_kafka.event.StaffAccountCreatedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.user_service.dto.request.CreateStaffRequest;
import com.dev.user_service.dto.response.UserResponse;
import com.dev.user_service.entities.User;
import com.dev.user_service.enums.UserRole;
import com.dev.user_service.enums.UserStatus;
import com.dev.user_service.exception.UserErrorCode;
import com.dev.user_service.repository.UserRepository;
import com.dev.user_service.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService{

    private final UserRepository userRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    @Override
    public UserResponse getCurrentUser(UUID userId) {
        // JWT vẫn hợp lệ nhưng user có thể đã bị xóa mềm sau khi token được cấp -> 404
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .map(this::buildResponse)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_FOUND));
    }


    @Override
    public UserResponse createStaff(CreateStaffRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new AppException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }

        String temporaryPassword = temporaryPasswordGenerator.generate();
        String passwordHash = passwordEncoder.encode(temporaryPassword);

        User savedUser;
        try {
            savedUser = transactionTemplate.execute(status -> userRepository.save(
                    User.builder()
                            .email(email)
                            .passwordHash(passwordHash)
                            .fullName(request.getFullName().trim())
                            .role(UserRole.STAFF)
                            // ACTIVE ngay: việc STAFF nhận được mật khẩu tạm qua email đã là bằng chứng sở hữu email
                            .status(UserStatus.ACTIVE)
                            .mustChangePassword(true)
                            .build()
            ));
        } catch (DataIntegrityViolationException e) {
            // Hai request tạo cùng email đồng thời — lớp chặn cuối là partial unique index
            throw new AppException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // Transaction đã commit. KHÔNG log payload event này vì chứa mật khẩu tạm dạng plaintext
        kafkaTemplate.send(
                KafkaTopics.USER_STAFF_ACCOUNT_CREATED,
                savedUser.getId().toString(),
                new StaffAccountCreatedEvent(
                        savedUser.getId(), savedUser.getEmail(), savedUser.getFullName(), temporaryPassword
                )
        ).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Publish user.staff-account-created thất bại, userId={}", savedUser.getId(), ex);
            }
        });

        return buildResponse(savedUser);
    }

    //helper method
    private UserResponse buildResponse(User user){
        return UserResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole())
                .oauthProvider(user.getOauthProvider())
                .status(user.getStatus())
                .mustChangePassword(user.isMustChangePassword())
                .build();
    }
}
