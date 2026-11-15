package com.dev.order_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.client.ProductCatalogClient;
import com.dev.order_service.dto.response.CheckInWindowResponse;
import com.dev.order_service.dto.response.ProductCatalog;
import com.dev.order_service.enums.OrderErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Khung giờ được quét vé (D44): từ {@code check-in-opens-before} trước giờ bắt đầu tới giờ kết thúc sự
 * kiện. Quét quá sớm từng làm vé "đã dùng" từ nhiều ngày trước và chặn cả việc hoàn tiền của buyer.
 */
@Component
public class CheckInWindowPolicy {

    // Event chưa có giờ kết thúc (dữ liệu cũ): cho quét tới 12 giờ sau giờ bắt đầu
    private static final Duration DEFAULT_EVENT_LENGTH = Duration.ofHours(12);

    private final ProductCatalogClient productCatalogClient;
    private final Duration opensBefore;

    public CheckInWindowPolicy(
            ProductCatalogClient productCatalogClient,
            @Value("${app.order.check-in-opens-before:PT3H}") Duration opensBefore
    ) {
        this.productCatalogClient = productCatalogClient;
        this.opensBefore = opensBefore;
    }

    /** Fail closed: không gọi được Product thì không quét (4010). Chỉ cần thông tin event, không tải sơ đồ ghế. */
    public void requireOpen(UUID eventId, Instant now) {
        ProductCatalog event;
        try {
            event = productCatalogClient.getEvent(eventId);
        } catch (AppException ex) {
            // Event không tồn tại báo như vé không tồn tại (D32): không cho dò event qua cổng quét
            if (ex.getErrorCode() == OrderErrorCode.EVENT_NOT_AVAILABLE) {
                throw new AppException(OrderErrorCode.TICKET_NOT_FOUND);
            }
            throw ex;
        }
        if (event == null) {
            throw new AppException(OrderErrorCode.TICKET_NOT_FOUND);
        }
        if (event.startAt() == null) {
            return;
        }
        Instant opensAt = event.startAt().minus(opensBefore);
        Instant closesAt = event.endAt() != null ? event.endAt() : event.startAt().plus(DEFAULT_EVENT_LENGTH);
        if (now.isBefore(opensAt) || now.isAfter(closesAt)) {
            throw new AppException(OrderErrorCode.CHECK_IN_NOT_OPEN,
                    new CheckInWindowResponse(opensAt, closesAt));
        }
    }
}
