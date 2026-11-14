package com.dev.order_service.service;

import com.dev.order_service.dto.request.AddOrderItemsRequest;
import com.dev.order_service.dto.request.CreateOrderRequest;
import com.dev.order_service.dto.response.AvailabilityResponse;
import com.dev.order_service.dto.response.CheckoutResponse;
import com.dev.order_service.dto.response.RefundQuoteResponse;
import com.dev.order_service.dto.response.OrderResponse;
import com.dev.order_service.dto.response.PageResponse;

import java.util.List;
import java.util.UUID;

public interface OrderService {
    OrderResponse create(UUID buyerId, CreateOrderRequest request, String idempotencyKey);
    PageResponse<OrderResponse> getOrders(UUID buyerId, List<String> statuses, int page, int size);
    OrderResponse getOrder(UUID buyerId, UUID orderId);
    OrderResponse addItems(UUID buyerId, UUID orderId, AddOrderItemsRequest request, String idempotencyKey);
    OrderResponse removeItem(UUID buyerId, UUID orderId, UUID itemId);
    OrderResponse updateItemQuantity(UUID buyerId, UUID orderId, UUID itemId, int quantity);
    OrderResponse cancel(UUID buyerId, UUID orderId);
    OrderResponse confirm(UUID buyerId, UUID orderId, String idempotencyKey);
    AvailabilityResponse getAvailability(UUID eventId);
    CheckoutResponse checkout(UUID buyerId, UUID orderId);
    RefundQuoteResponse getRefundQuote(UUID buyerId, UUID orderId);
    OrderResponse requestRefund(UUID buyerId, UUID orderId);
}
