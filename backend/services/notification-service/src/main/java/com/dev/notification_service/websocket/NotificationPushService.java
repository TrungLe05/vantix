package com.dev.notification_service.websocket;

import com.dev.notification_service.dto.response.NotificationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giữ các phiên WebSocket theo user và đẩy thông báo mới. Một user có thể mở nhiều tab/thiết bị.
 * Đẩy là best-effort: client mất kết nối vẫn thấy thông báo khi gọi lại API danh sách.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationPushService {

    // Giới hạn để một client chậm không giữ luồng gửi của người khác
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;

    private final JsonMapper jsonMapper;
    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public WebSocketSession register(UUID userId, WebSocketSession session) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        sessions.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(safe);
        return safe;
    }

    public void unregister(UUID userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (id, set) -> {
            set.removeIf(s -> s.getId().equals(session.getId()));
            return set.isEmpty() ? null : set;
        });
    }

    public int sessionCount(UUID userId) {
        Set<WebSocketSession> set = sessions.get(userId);
        return set == null ? 0 : set.size();
    }

    public void push(UUID userId, NotificationResponse notification) {
        Set<WebSocketSession> targets = sessions.get(userId);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(jsonMapper.writeValueAsString(
                Map.of("type", "notification", "notification", notification)));
        for (WebSocketSession session : targets) {
            try {
                session.sendMessage(message);
            } catch (IOException | RuntimeException ex) {
                log.debug("Đẩy WebSocket thất bại userId={} sessionId={} ({})",
                        userId, session.getId(), ex.getClass().getSimpleName());
                unregister(userId, session);
                closeQuietly(session);
            }
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException ignored) {
            // phiên đã hỏng, không còn gì để làm
        }
    }
}
