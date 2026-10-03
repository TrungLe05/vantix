package com.dev.commonlib_kafka.event;

import java.util.UUID;

public record StaffAccountCreatedEvent(
        UUID userId,
        String email,
        String fullName,
        String temporaryPassword
) {
}
