package com.dev.commonlib_kafka.event;

import java.util.UUID;

public record ForgotPasswordRequestedEvent(
        UUID userId,
        String email,
        String fullName,
        String otpCode
) {
}
