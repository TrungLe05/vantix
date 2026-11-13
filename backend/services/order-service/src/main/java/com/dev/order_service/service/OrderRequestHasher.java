package com.dev.order_service.service;

import com.dev.order_service.dto.request.OrderItemRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class OrderRequestHasher {

    public String hash(UUID eventId, UUID orderId, List<OrderItemRequest> items) {
        StringBuilder canonical = new StringBuilder()
                .append(eventId == null ? "" : eventId)
                .append('|')
                .append(orderId == null ? "" : orderId);
        items.forEach(item -> canonical.append('|')
                .append(item.getTicketTypeId())
                .append(':')
                .append(item.getSeatId() == null ? "" : item.getSeatId())
                .append(':')
                .append(item.getQuantity()));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
