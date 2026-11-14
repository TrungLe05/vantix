package com.dev.order_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.commonlib_security.security.SecurityUtils;
import com.dev.order_service.dto.request.AddOrderItemsRequest;
import com.dev.order_service.dto.request.CheckInRequest;
import com.dev.order_service.dto.request.CreateOrderRequest;
import com.dev.order_service.dto.request.UpdateOrderItemQuantityRequest;
import com.dev.order_service.dto.response.AvailabilityResponse;
import com.dev.order_service.dto.response.CheckInResponse;
import com.dev.order_service.dto.response.CheckoutResponse;
import com.dev.order_service.dto.response.RefundQuoteResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.PageResponse;
import com.dev.order_service.service.OrderService;
import com.dev.order_service.service.TicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final TicketService ticketService;

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
    public ApiResponse<PageResponse<OrderResponse>> list(
            // Lặp lại hoặc phân tách dấu phẩy: ?status=CART&status=EXPIRED hoặc ?status=CART,EXPIRED
            @RequestParam(required = false, name = "status") List<String> statuses,
            @RequestParam(defaultValue = "0", name = "page") @Min(0) int page,
            @RequestParam(defaultValue = "20", name = "size") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.<PageResponse<OrderResponse>>builder()
                .result(orderService.getOrders(SecurityUtils.getCurrentUserId(), statuses, page, size))
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

    @PostMapping("/{orderId}/checkout")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<CheckoutResponse> checkout(@PathVariable UUID orderId) {
        return ApiResponse.<CheckoutResponse>builder()
                .result(orderService.checkout(SecurityUtils.getCurrentUserId(), orderId))
                .build();
    }

    @GetMapping("/{orderId}/refund-quote")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<RefundQuoteResponse> refundQuote(@PathVariable UUID orderId) {
        return ApiResponse.<RefundQuoteResponse>builder()
                .result(orderService.getRefundQuote(SecurityUtils.getCurrentUserId(), orderId))
                .build();
    }

    @PostMapping("/{orderId}/refund")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<OrderResponse> refund(@PathVariable UUID orderId) {
        return ApiResponse.<OrderResponse>builder()
                .result(orderService.requestRefund(SecurityUtils.getCurrentUserId(), orderId))
                .build();
    }

    // OR12 — ảnh QR của vé. Không khai báo produces để lỗi vẫn trả JSON qua GlobalExceptionHandler
    @GetMapping("/{orderId}/tickets/{ticketId}/qr")
    @PreAuthorize("hasRole('BUYER')")
    public ResponseEntity<byte[]> ticketQr(@PathVariable UUID orderId, @PathVariable UUID ticketId) {
        byte[] png = ticketService.qrPng(SecurityUtils.getCurrentUserId(), orderId, ticketId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                // Ảnh chứa mã vào cổng: không cho proxy/trình duyệt dùng chung lưu lại
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    // OR13 — quét vé ở cổng
    @PostMapping("/tickets/check-in")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<CheckInResponse> checkIn(@Valid @RequestBody CheckInRequest request) {
        return ApiResponse.<CheckInResponse>builder()
                .result(ticketService.checkIn(
                        SecurityUtils.getCurrentUserId(), request.getEventId(), request.getCode()))
                .build();
    }

    @GetMapping("/events/{eventId}/availability")
    public ApiResponse<AvailabilityResponse> availability(@PathVariable UUID eventId) {
        return ApiResponse.<AvailabilityResponse>builder()
                .result(orderService.getAvailability(eventId))
                .build();
    }
}
