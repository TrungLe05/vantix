package com.dev.product_service.validation;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.product_service.exception.ProductErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class EventTimeValidator {

    /** Validate startAt/endAt. Ném 3106 nếu sai. */
    public void validateEventWindow(Instant startAt, Instant endAt) {
        if (!startAt.isAfter(Instant.now())) {
            throw new AppException(ProductErrorCode.EVENT_TIME_RANGE_INVALID);
        }
        if (!endAt.isAfter(startAt)) {
            throw new AppException(ProductErrorCode.EVENT_TIME_RANGE_INVALID);
        }
    }

    /** Validate salesStartAt/salesEndAt so với startAt. Ném 3107 nếu sai. Cho phép cả hai null. */
    public void validateSalesWindow(Instant salesStartAt, Instant salesEndAt, Instant startAt) {
        if (salesStartAt != null && salesEndAt != null && !salesStartAt.isBefore(salesEndAt)) {
            throw new AppException(ProductErrorCode.SALES_WINDOW_INVALID);
        }
        if (salesEndAt != null && salesEndAt.isAfter(startAt)) {
            throw new AppException(ProductErrorCode.SALES_WINDOW_INVALID);
        }
    }
}