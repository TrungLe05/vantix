package com.dev.commonlib_kafka.topic;

public final class KafkaTopics {

    private KafkaTopics() {
        // utility class, không cho khởi tạo
    }

    // user-service (P1)
    public static final String USER_EMAIL_VERIFICATION_REQUESTED = "user.email-verification-requested";
    public static final String USER_FORGOT_PASSWORD_REQUESTED = "user.forgot-password-requested";
    public static final String USER_STAFF_ACCOUNT_CREATED = "user.staff-account-created";
}