package com.dev.notification_service.service;

import com.dev.notification_service.dto.response.NotificationResponse;
import com.dev.notification_service.dto.response.PageResponse;
import com.dev.notification_service.dto.response.UnreadCountResponse;

import java.util.UUID;

public interface NotificationService {
    PageResponse<NotificationResponse> list(UUID userId, int page, int size);
    UnreadCountResponse unreadCount(UUID userId);
    NotificationResponse markRead(UUID userId, UUID notificationId);
    int markAllRead(UUID userId);
}
