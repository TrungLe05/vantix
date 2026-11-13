package com.dev.order_service.client;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.order_service.dto.response.ProductCatalog;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@FeignClient(name = "product-service", url = "${app.product-service.base-url}")
public interface ProductCatalogFeignClient {

    @GetMapping("/events/{eventId}")
    ApiResponse<ProductCatalog> getEvent(@PathVariable UUID eventId);

    @GetMapping("/events/{eventId}/seat-map")
    ApiResponse<ProductCatalog.SeatMap> getSeatMap(@PathVariable UUID eventId);
}
