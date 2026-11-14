package com.dev.notification_service.client;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Lấy email người nhận từ user-service (D34). Order/Payment không giữ email nên Notification phải
 * hỏi nơi sở hữu dữ liệu, thay vì lưu bản sao PII của mọi user.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UserContactClient {

    // Danh tính của service gọi nội bộ: role INTERNAL chỉ đến được từ mạng nội bộ (Gateway xóa X-User-*)
    private static final String SERVICE_ID = "00000000-0000-0000-0000-000000000000";
    private static final String INTERNAL_ROLE = "INTERNAL";

    private final UserContactFeignClient feignClient;

    /**
     * @return rỗng nếu user không còn tồn tại (không thử lại)
     * @throws UserServiceUnavailableException lỗi mạng/5xx — caller thử lại sau
     */
    public Optional<UserContactFeignClient.UserContact> findContact(UUID userId) {
        try {
            var response = feignClient.getContact(SERVICE_ID, INTERNAL_ROLE, userId);
            return Optional.ofNullable(response == null ? null : response.getResult());
        } catch (FeignException ex) {
            if (ex.status() == 404) {
                return Optional.empty();
            }
            log.warn("User-service lookup failed: userId={}, httpStatus={}, type={}",
                    userId, ex.status() < 0 ? "no-response" : ex.status(), ex.getClass().getSimpleName());
            throw new UserServiceUnavailableException();
        }
    }

    public static class UserServiceUnavailableException extends RuntimeException {
        public UserServiceUnavailableException() {
            super("user-service unavailable");
        }
    }
}
