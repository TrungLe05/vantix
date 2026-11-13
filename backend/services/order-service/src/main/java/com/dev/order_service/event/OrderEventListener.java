package com.dev.order_service.event;

import com.dev.commonlib_kafka.event.EventCancelledEvent;
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

    @KafkaListener(topics = KafkaTopics.PRODUCT_EVENT_CANCELLED, groupId = "order-service")
    public void onEventCancelled(String payload) {
        try {
            EventCancelledEvent event = jsonMapper.readValue(payload, EventCancelledEvent.class);
            orderCommandService.cancelUnpaidOrdersForEvent(event.eventId());
        } catch (Exception ex) {
            log.error("Không thể đọc product.event-cancelled; Kafka sẽ retry", ex);
            throw new IllegalStateException("Invalid product.event-cancelled payload", ex);
        }
    }
}
