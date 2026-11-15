package com.dev.notification_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.notification_service.dto.response.NotificationResponse;
import com.dev.notification_service.dto.response.PageResponse;
import com.dev.notification_service.dto.response.UnreadCountResponse;
import com.dev.notification_service.entity.Notification;
import com.dev.notification_service.enums.NotificationErrorCode;
import com.dev.notification_service.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UUID userId, int page, int size) {
        Page<Notification> result = notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(
                userId, PageRequest.of(page, size));
        return PageResponse.<NotificationResponse>builder()
                .content(result.getContent().stream().map(NotificationServiceImpl::toResponse).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(UUID userId) {
        return new UnreadCountResponse(notificationRepository.countByUserIdAndReadAtIsNull(userId));
    }

    @Override
    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new AppException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        // Đánh dấu lại không đổi mốc đọc đầu tiên (idempotent)
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
        }
        return toResponse(notification);
    }

    @Override
    @Transactional
    public int markAllRead(UUID userId) {
        return notificationRepository.markAllRead(userId, Instant.now());
    }

    static NotificationResponse toResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getType())
                .title(notification.getTitle())
                .body(notification.getBody())
                .link(notification.getLink())
                .orderId(notification.getOrderId())
                .read(notification.getReadAt() != null)
                .createdAt(notification.getCreatedAt())
                .build();
    }
}
