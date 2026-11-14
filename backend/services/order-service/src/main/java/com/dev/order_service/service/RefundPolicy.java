package com.dev.order_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Chính sách hoàn vé khi buyer tự hủy (D16): còn ≥ 7 ngày tới giờ bắt đầu sự kiện hoàn 100%,
 * từ 24 giờ tới dưới 7 ngày hoàn 50%, dưới 24 giờ không hoàn. ADMIN hủy sự kiện luôn hoàn 100%
 * (không đi qua class này).
 */
public final class RefundPolicy {

    private static final Duration FULL_REFUND_WINDOW = Duration.ofDays(7);
    private static final Duration HALF_REFUND_WINDOW = Duration.ofHours(24);

    private RefundPolicy() {
    }

    public static int percent(Instant now, Instant eventStartAt) {
        if (eventStartAt == null) {
            // Không xác định được giờ diễn → không tự hoàn (fail closed, tránh hoàn sai)
            return 0;
        }
        Duration untilStart = Duration.between(now, eventStartAt);
        if (untilStart.compareTo(FULL_REFUND_WINDOW) >= 0) {
            return 100;
        }
        if (untilStart.compareTo(HALF_REFUND_WINDOW) >= 0) {
            return 50;
        }
        return 0;
    }

    /** {@code paidAmount × percent}, làm tròn HALF_UP tới cent. */
    public static BigDecimal amount(BigDecimal paidAmount, int percent) {
        return paidAmount.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
