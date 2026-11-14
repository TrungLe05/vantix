package com.dev.notification_service.config;

import com.dev.notification_service.websocket.NotificationWebSocketHandler;
import com.dev.notification_service.websocket.WsTicketHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final NotificationWebSocketHandler handler;
    private final WsTicketHandshakeInterceptor ticketInterceptor;

    // Chỉ frontend được mở kết nối: chặn trang lạ dùng ticket bị lộ (cross-site WebSocket hijacking)
    @Value("${app.notification.frontend-base-url:http://localhost:3000}")
    private String frontendOrigin;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/notifications/ws")
                .addInterceptors(ticketInterceptor)
                .setAllowedOrigins(frontendOrigin);
    }
}
