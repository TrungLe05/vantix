package com.dev.payment_service.event;

import com.dev.commonlib_kafka.event.RefundRequestedEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.payment_service.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventListener {

    private final JsonMapper jsonMapper;
    private final RefundService refundService;

    // groupId cấu hình được để stack test/E2E dùng chung cluster Kafka dev không tranh partition
    @KafkaListener(topics = KafkaTopics.ORDER_REFUND_REQUESTED,
            groupId = "${app.payment.kafka-consumer-group:payment-service}")
    public void onRefundRequested(String payload) {
        try {
            refundService.handleRefundRequested(jsonMapper.readValue(payload, RefundRequestedEvent.class));
        } catch (Exception ex) {
            // Chỉ log loại lỗi: payload có buyerId/số tiền, không cần đưa vào log
            log.error("Không xử lý được order.refund-requested ({}); Kafka sẽ retry",
                    ex.getClass().getSimpleName());
            throw new IllegalStateException("Cannot process order.refund-requested", ex);
        }
    }
}
