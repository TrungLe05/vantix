package com.dev.notification_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class WsTicketResponse {
    // Dùng một lần cho handshake GET /api/notifications/ws?ticket=...
    private String ticket;
    private Instant expiresAt;
}
