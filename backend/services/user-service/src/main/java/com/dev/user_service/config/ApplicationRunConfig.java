package com.dev.user_service.config;

import com.dev.commonlibjwt.jwt.JwtProperties;
import com.dev.user_service.entities.User;
import com.dev.user_service.enums.UserRole;
import com.dev.user_service.enums.UserStatus;
import com.dev.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationRunConfig implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.run-config.default-admin.username}")
    private String adminUsername;

    @Value("${app.run-config.default-admin.password}")
    private String adminPassword;

    @Value("${app.run-config.default-staff.username}")
    private String staffUsername;

    @Value("${app.run-config.default-staff.password}")
    private String staffPassword;

    @Override
    public void run(ApplicationArguments args) {
        seedIfAbsent(adminUsername, adminPassword, UserRole.ADMIN);
        seedIfAbsent(staffUsername, staffPassword, UserRole.STAFF);
    }

    private void seedIfAbsent(String rawEmail, String rawPassword, UserRole role) {
        // Cùng quy tắc chuẩn hóa với mọi API khác, nếu không login sẽ không tìm thấy user
        String email = rawEmail.trim().toLowerCase();

        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            log.info("Bỏ qua seed {} vì tài khoản đã tồn tại", role);
            return;
        }

        // Không set id: để @GeneratedValue sinh, tránh save() rẽ sang merge()
        userRepository.save(User.builder()
                .email(email)
                .fullName(role.name())
                .role(role)
                .status(UserStatus.ACTIVE)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .mustChangePassword(false)
                .build());

        log.info("Đã tạo tài khoản seed {}", role);
    }
}