package com.dev.notification_service.dto.response;

import com.dev.notification_service.enums.NotificationType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class NotificationResponse {
    private UUID id;
    private NotificationType type;
    private String title;
    private String body;
    private String link;
    private UUID orderId;
    private boolean read;
    private Instant createdAt;
}
