package com.dev.notification_service.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket một lần cho handshake WebSocket (D35). Trình duyệt không gửi được header Authorization khi mở
 * WebSocket, còn đặt JWT vào URL thì token sống 15 phút dễ lọt vào log/proxy. Ticket chỉ sống 30 giây,
 * dùng được đúng một lần, cấp qua API REST đã đi qua Gateway (JWT hợp lệ).
 * Lưu trong bộ nhớ: đủ cho một instance; chạy nhiều instance thì phải chuyển sang Redis cùng lúc với
 * việc phát thông báo giữa các instance (P9).
 */
@Component
public class WsTicketService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();
    private final Duration ttl;

    public WsTicketService(@Value("${app.notification.ws-ticket-ttl:PT30S}") Duration ttl) {
        this.ttl = ttl;
    }

    public Issued issue(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(ttl);
        tickets.put(ticket, new Entry(userId, expiresAt));
        return new Issued(ticket, expiresAt);
    }

    /** Lấy và xóa ticket: lần dùng thứ hai (hoặc ticket hết hạn) trả rỗng. */
    public Optional<UUID> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Entry entry = tickets.remove(ticket);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }

    // Ticket được cấp mà không bao giờ dùng: dọn để map không phình
    @Scheduled(fixedDelay = 60_000)
    public void purgeExpired() {
        Instant now = Instant.now();
        tickets.values().removeIf(entry -> entry.expiresAt().isBefore(now));
    }

    public record Issued(String ticket, Instant expiresAt) {
    }

    private record Entry(UUID userId, Instant expiresAt) {
    }
}
