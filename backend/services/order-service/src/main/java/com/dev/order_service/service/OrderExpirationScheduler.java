package com.dev.order_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderExpirationScheduler {

    private final OrderCommandService orderCommandService;

    @Scheduled(fixedDelay = 1000)
    public void expireDueOrders() {
        int expired = orderCommandService.expireDueOrders();
        if (expired > 0) {
            log.info("Đã xử lý {} Order quá hạn", expired);
        }
    }
}
