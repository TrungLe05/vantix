package com.dev.order_service.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Ba mốc hoàn vé của D16, kiểm tra đúng tại biên. */
class RefundPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    @Test
    void sevenDaysOrMoreIsFullRefund() {
        assertThat(RefundPolicy.percent(NOW, NOW.plus(Duration.ofDays(30)))).isEqualTo(100);
        assertThat(RefundPolicy.percent(NOW, NOW.plus(Duration.ofDays(7)))).isEqualTo(100);
    }

    @Test
    void fromTwentyFourHoursToJustUnderSevenDaysIsHalfRefund() {
        assertThat(RefundPolicy.percent(NOW, NOW.plus(Duration.ofDays(7)).minusSeconds(1))).isEqualTo(50);
        assertThat(RefundPolicy.percent(NOW, NOW.plus(Duration.ofHours(24)))).isEqualTo(50);
    }

    @Test
    void underTwentyFourHoursStartedOrUnknownStartIsNoRefund() {
        assertThat(RefundPolicy.percent(NOW, NOW.plus(Duration.ofHours(24)).minusSeconds(1))).isZero();
        assertThat(RefundPolicy.percent(NOW, NOW.minus(Duration.ofHours(1)))).isZero();
        assertThat(RefundPolicy.percent(NOW, null)).isZero();
    }

    @Test
    void amountIsRoundedHalfUpToCents() {
        assertThat(RefundPolicy.amount(new BigDecimal("170.00"), 50)).isEqualByComparingTo("85.00");
        assertThat(RefundPolicy.amount(new BigDecimal("20.25"), 50)).isEqualByComparingTo("10.13");
        assertThat(RefundPolicy.amount(new BigDecimal("20.25"), 100)).isEqualByComparingTo("20.25");
        assertThat(RefundPolicy.amount(new BigDecimal("20.25"), 0)).isEqualByComparingTo("0.00");
    }
}
