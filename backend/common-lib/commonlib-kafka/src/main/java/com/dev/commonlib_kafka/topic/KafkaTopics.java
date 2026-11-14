package com.dev.commonlib_kafka.topic;

public final class KafkaTopics {

    private KafkaTopics() {
        // utility class, không cho khởi tạo
    }

    // user-service (P1)
    public static final String USER_EMAIL_VERIFICATION_REQUESTED = "user.email-verification-requested";
    public static final String USER_FORGOT_PASSWORD_REQUESTED = "user.forgot-password-requested";
    public static final String USER_STAFF_ACCOUNT_CREATED = "user.staff-account-created";

    // product-service (P2)
    public static final String PRODUCT_EVENT_CANCELLED = "product.event-cancelled";

    // order-service (P3)
    public static final String ORDER_CART_ITEM_ADDED = "cart.item_added";

    // order-service → payment-service (P4)
    public static final String ORDER_REFUND_REQUESTED = "order.refund-requested";

    // payment-service → order-service (P4)
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String REFUND_COMPLETED = "refund.completed";
    public static final String REFUND_FAILED = "refund.failed";
}
