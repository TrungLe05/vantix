package com.dev.product_service.services;

import com.dev.product_service.dto.response.EventDetailResponse;
import com.dev.product_service.dto.response.EventResponse;
import com.dev.product_service.dto.response.EventSummaryResponse;
import com.dev.product_service.dto.response.PageResponse;
import com.dev.product_service.dto.request.CreateEventRequest;
import com.dev.product_service.dto.request.UpdateEventRequest;

import java.util.UUID;

public interface EventService {
    EventResponse create(CreateEventRequest request);
    EventResponse update(UUID eventId, UpdateEventRequest request);

    /** PR05 — DRAFT → PUBLISHED (với validation đầy đủ). */
    EventResponse publish(UUID eventId);

    /** PR06 — DRAFT/PUBLISHED → CANCELLED, publish Kafka event.cancelled. */
    EventResponse cancel(UUID eventId);

    /** PR07 — Danh sách PUBLISHED phân trang, chỉ event chưa diễn ra. */
    PageResponse<EventSummaryResponse> getPublishedList(int page, int size);

    /** PR08 — Chi tiết event (PUBLISHED hoặc CANCELLED), ẩn DRAFT bằng cùng lỗi NOT_FOUND. */
    EventDetailResponse getDetail(UUID eventId);
}