package com.dev.notification_service.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;

/**
 * WebSocket thuần (không STOMP): kênh chỉ một chiều server → client nên không cần broker/subscription,
 * và frontend dùng được WebSocket có sẵn của trình duyệt mà không thêm thư viện.
 */
@Component
@RequiredArgsConstructor
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    static final String USER_ID_ATTRIBUTE = "notificationUserId";
    private static final String DECORATED_ATTRIBUTE = "notificationDecoratedSession";

    private final NotificationPushService pushService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = (UUID) session.getAttributes().get(USER_ID_ATTRIBUTE);
        session.getAttributes().put(DECORATED_ATTRIBUTE, pushService.register(userId, session));
    }

    // Client gửi gì cũng bỏ qua (có thể là ping giữ kết nối)
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = (UUID) session.getAttributes().get(USER_ID_ATTRIBUTE);
        if (userId != null) {
            pushService.unregister(userId, session);
        }
    }
}
