package com.dev.order_service.event;

import com.dev.commonlib_kafka.topic.KafkaTopics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chặn hồi quy: thiếu spring-boot-starter-kafka (Boot 4 tách auto-configuration Kafka) thì @KafkaListener
 * bị bỏ qua âm thầm — order-service không bao giờ nhận product.event-cancelled. Test khác gọi thẳng
 * method listener nên không phát hiện được; ở đây kiểm tra container thật sự được đăng ký.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderEventListenerRegistrationTest {

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Test
    void eventCancelledListenerIsRegisteredWithConfiguredGroup() {
        assertThat(registry.getListenerContainers())
                .anySatisfy((MessageListenerContainer container) -> {
                    assertThat(container.getContainerProperties().getTopics())
                            .containsExactly(KafkaTopics.PRODUCT_EVENT_CANCELLED);
                    assertThat(container.getGroupId()).isEqualTo("order-service");
                });
    }
}
