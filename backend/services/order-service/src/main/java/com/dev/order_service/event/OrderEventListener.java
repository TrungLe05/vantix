package com.dev.order_service.event;

import com.dev.commonlib_kafka.event.EventCancelledEvent;
import com.dev.commonlib_kafka.event.PaymentFailedEvent;
import com.dev.commonlib_kafka.event.PaymentSucceededEvent;
import com.dev.commonlib_kafka.event.RefundCompletedEvent;
import com.dev.commonlib_kafka.event.RefundFailedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.order_service.service.OrderCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventListener {

    private final JsonMapper jsonMapper;
    private final OrderCommandService orderCommandService;

    // groupId cấu hình được để một stack test/E2E dùng chung cluster Kafka dev không tranh partition
    // với order-service dev (cùng group thì mỗi message chỉ một bên nhận)
    @KafkaListener(topics = KafkaTopics.PRODUCT_EVENT_CANCELLED,
            groupId = "${app.order.kafka-consumer-group:order-service}")
    public void onEventCancelled(String payload) {
        try {
            EventCancelledEvent event = jsonMapper.readValue(payload, EventCancelledEvent.class);
            orderCommandService.cancelUnpaidOrdersForEvent(event.eventId());
        } catch (Exception ex) {
            log.error("Không thể đọc product.event-cancelled; Kafka sẽ retry", ex);
            throw new IllegalStateException("Invalid product.event-cancelled payload", ex);
        }
    }

    // P4 — kết quả từ payment-service. Mọi handler idempotent (Kafka at-least-once, outbox có thể phát lặp).

    @KafkaListener(topics = KafkaTopics.PAYMENT_SUCCEEDED,
            groupId = "${app.order.kafka-consumer-group:order-service}")
    public void onPaymentSucceeded(String payload) {
        consume(KafkaTopics.PAYMENT_SUCCEEDED, () -> orderCommandService.handlePaymentSucceeded(
                jsonMapper.readValue(payload, PaymentSucceededEvent.class)));
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_FAILED,
            groupId = "${app.order.kafka-consumer-group:order-service}")
    public void onPaymentFailed(String payload) {
        consume(KafkaTopics.PAYMENT_FAILED, () -> orderCommandService.handlePaymentFailed(
                jsonMapper.readValue(payload, PaymentFailedEvent.class)));
    }

    @KafkaListener(topics = KafkaTopics.REFUND_COMPLETED,
            groupId = "${app.order.kafka-consumer-group:order-service}")
    public void onRefundCompleted(String payload) {
        consume(KafkaTopics.REFUND_COMPLETED, () -> orderCommandService.handleRefundCompleted(
                jsonMapper.readValue(payload, RefundCompletedEvent.class)));
    }

    @KafkaListener(topics = KafkaTopics.REFUND_FAILED,
            groupId = "${app.order.kafka-consumer-group:order-service}")
    public void onRefundFailed(String payload) {
        consume(KafkaTopics.REFUND_FAILED, () -> orderCommandService.handleRefundFailed(
                jsonMapper.readValue(payload, RefundFailedEvent.class)));
    }

    private void consume(String topic, Runnable handler) {
        try {
            handler.run();
        } catch (Exception ex) {
            // Không log payload (có buyerId, số tiền); chỉ loại lỗi
            log.error("Không xử lý được {} ({}); Kafka sẽ retry", topic, ex.getClass().getSimpleName());
            throw new IllegalStateException("Cannot process " + topic, ex);
        }
    }
}
