package com.dev.order_service.client;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.dto.response.ProductCatalog;
import com.dev.order_service.enums.OrderErrorCode;
import feign.FeignException;
import feign.Request;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProductCatalogClient {

    private static final Pattern RESPONSE_CODE_PATTERN = Pattern.compile("\\\"code\\\"\\s*:\\s*(\\d+)");

    private final ProductCatalogFeignClient productClient;

    public ProductCatalog getEvent(UUID eventId) {
        ApiResponse<ProductCatalog> response;
        try {
            response = productClient.getEvent(eventId);
        } catch (FeignException ex) {
            logFailure("get-event", eventId, ex);
            if (ex.status() == 404) {
                throw new AppException(OrderErrorCode.EVENT_NOT_AVAILABLE);
            }
            throw new AppException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        if (response == null || response.getResult() == null) {
            throw new AppException(OrderErrorCode.EVENT_NOT_AVAILABLE);
        }
        return response.getResult();
    }

    public ProductCatalog.SeatMap getSeatMap(UUID eventId) {
        ApiResponse<ProductCatalog.SeatMap> response;
        try {
            response = productClient.getSeatMap(eventId);
        } catch (FeignException ex) {
            logFailure("get-seat-map", eventId, ex);
            if (ex.status() == 404) {
                throw new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY);
            }
            throw new AppException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        if (response == null || response.getResult() == null) {
            throw new AppException(OrderErrorCode.EVENT_INVENTORY_NOT_READY);
        }
        return response.getResult();
    }

    private void logFailure(String operation, UUID eventId, FeignException exception) {
        Request request = exception.request();
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        log.error(
                "Product-service request failed: operation={}, eventId={}, httpStatus={}, method={}, url={}, " +
                        "upstreamCode={}, exceptionType={}, rootCauseType={}, rootCauseMessage={}",
                operation,
                eventId,
                exception.status() < 0 ? "no-response" : exception.status(),
                request == null ? "unknown" : request.httpMethod(),
                request == null ? "unknown" : request.url(),
                extractResponseCode(exception.contentUTF8()),
                exception.getClass().getSimpleName(),
                rootCause == exception ? "none" : rootCause.getClass().getSimpleName(),
                rootCause == exception ? "none" : rootCause.getMessage()
        );
    }

    private String extractResponseCode(String responseBody) {
        Matcher matcher = RESPONSE_CODE_PATTERN.matcher(responseBody);
        return matcher.find() ? matcher.group(1) : "unknown";
    }
}
