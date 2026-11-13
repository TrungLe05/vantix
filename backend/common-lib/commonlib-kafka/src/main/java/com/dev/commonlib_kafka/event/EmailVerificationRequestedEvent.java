package com.dev.commonlib_kafka.event;

import java.util.UUID;

public record EmailVerificationRequestedEvent(
        UUID userId,
        String email,
        String fullName,
        String otpCode
) {
}
