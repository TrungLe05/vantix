package com.dev.order_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.commonlib_security.security.SecurityUtils;
import com.dev.order_service.dto.request.AddOrderItemsRequest;
import com.dev.order_service.dto.request.CreateOrderRequest;
import com.dev.order_service.dto.request.UpdateOrderItemQuantityRequest;
import com.dev.order_service.dto.response.AvailabilityResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasRole('BUYER')")
    public ResponseEntity<ApiResponse<OrderResponse>> create(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        OrderResponse result = orderService.create(SecurityUtils.getCurrentUserId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<OrderResponse>builder().result(result).build());
    }

    @GetMapping
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<List<OrderResponse>> list() {
        return ApiResponse.<List<OrderResponse>>builder()
                .result(orderService.getOrders(SecurityUtils.getCurrentUserId()))
                .build();
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<OrderResponse> get(@PathVariable UUID orderId) {
        return ApiResponse.<OrderResponse>builder()
                .result(orderService.getOrder(SecurityUtils.getCurrentUserId(), orderId))
                .build();
    }

    @PostMapping("/{orderId}/items")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<OrderResponse> addItems(
            @PathVariable UUID orderId,
            @Valid @RequestBody AddOrderItemsRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.<OrderResponse>builder()
                .result(orderService.addItems(
                        SecurityUtils.getCurrentUserId(), orderId, request, idempotencyKey
                ))
                .build();
    }

    @DeleteMapping("/{orderId}/items/{itemId}")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<OrderResponse> removeItem(
            @PathVariable UUID orderId,
            @PathVariable UUID itemId
    ) {
        return ApiResponse.<OrderResponse>builder()
                .result(orderService.removeItem(SecurityUtils.getCurrentUserId(), orderId, itemId))
                .build();
    }

    @PatchMapping("/{orderId}/items/{itemId}")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<OrderResponse> updateItemQuantity(
            @PathVariable UUID orderId,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateOrderItemQuantityRequest request
    ) {
        return ApiResponse.<OrderResponse>builder()
                .result(orderService.updateItemQuantity(
                        SecurityUtils.getCurrentUserId(), orderId, itemId, request.getQuantity()
                ))
                .build();
    }

    @DeleteMapping("/{orderId}")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<OrderResponse> cancel(@PathVariable UUID orderId) {
        return ApiResponse.<OrderResponse>builder()
                .result(orderService.cancel(SecurityUtils.getCurrentUserId(), orderId))
                .build();
    }

    @PostMapping("/{orderId}/confirm")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<OrderResponse> confirm(
            @PathVariable UUID orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.<OrderResponse>builder()
                .result(orderService.confirm(SecurityUtils.getCurrentUserId(), orderId, idempotencyKey))
                .build();
    }

    @GetMapping("/events/{eventId}/availability")
    public ApiResponse<AvailabilityResponse> availability(@PathVariable UUID eventId) {
        return ApiResponse.<AvailabilityResponse>builder()
                .result(orderService.getAvailability(eventId))
                .build();
    }
}
