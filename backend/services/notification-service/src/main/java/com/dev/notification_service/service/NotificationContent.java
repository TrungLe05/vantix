package com.dev.notification_service.service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Nội dung thông báo hiển thị cho người dùng. Tiếng Anh vì UI của Vantix đang dùng tiếng Anh.
 * Notification không biết tên sự kiện/vé (không gọi Product): nội dung dẫn người dùng về trang đơn hàng.
 */
public final class NotificationContent {

    private NotificationContent() {
    }

    public record Content(String title, String body, String link) {
    }

    public static Content ticketsIssued(UUID orderId, int ticketCount, BigDecimal total, String currency) {
        String tickets = ticketCount == 1 ? "1 ticket is" : ticketCount + " tickets are";
        return new Content(
                "Your tickets are confirmed",
                "We received your payment of " + money(total, currency) + ". " + tickets
                        + " ready - open your order to show the QR code at the entrance.",
                orderLink(orderId));
    }

    public static Content refundRequested(UUID orderId, String reason, BigDecimal amount, String currency) {
        String value = money(amount, currency);
        String body = switch (reason == null ? "" : reason) {
            case "EVENT_CANCELLED" -> "An event you booked was cancelled. A full refund of " + value
                    + " is on its way to your payment method.";
            case "LATE_PAYMENT" -> "Your payment arrived after your ticket hold had ended, so the tickets were "
                    + "released. A full refund of " + value + " is on its way to your payment method.";
            default -> "Your order was cancelled. A refund of " + value + " is being processed.";
        };
        return new Content("Refund in progress", body, orderLink(orderId));
    }

    public static Content refundCompleted(UUID orderId, BigDecimal amount, String currency) {
        return new Content(
                "Refund completed",
                money(amount, currency) + " has been returned to your payment method. "
                        + "It may take a few business days to appear on your statement.",
                orderLink(orderId));
    }

    private static String orderLink(UUID orderId) {
        return "/orders/" + orderId;
    }

    private static String money(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
        if (currency != null && !"USD".equals(currency)) {
            format.setCurrency(java.util.Currency.getInstance(currency));
        }
        return format.format(amount == null ? BigDecimal.ZERO : amount);
    }
}
