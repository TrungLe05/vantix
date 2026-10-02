package com.dev.product_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.product_service.dto.request.CreateEventRequest;
import com.dev.product_service.dto.request.UpdateEventRequest;
import com.dev.product_service.dto.response.EventDetailResponse;
import com.dev.product_service.dto.response.EventResponse;
import com.dev.product_service.dto.response.EventSummaryResponse;
import com.dev.product_service.dto.response.PageResponse;
import com.dev.product_service.services.EventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    // ── PR03 ── Tạo sự kiện ──────────────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EventResponse>> create(@Valid @RequestBody CreateEventRequest request) {
        EventResponse result = eventService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<EventResponse>builder()
                        .message("Tạo sự kiện thành công")
                        .result(result)
                        .build());
    }

    // ── PR04 ── Sửa sự kiện ──────────────────────────────────────────────────
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<EventResponse> update(
            @PathVariable("id") UUID id, @Valid @RequestBody UpdateEventRequest request
    ) {
        EventResponse result = eventService.update(id, request);
        return ApiResponse.<EventResponse>builder().result(result).build();
    }

    // ── PR05 ── Publish sự kiện (DRAFT → PUBLISHED) ───────────────────────
    @PatchMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<EventResponse> publish(@PathVariable("id") UUID id) {
        EventResponse result = eventService.publish(id);
        return ApiResponse.<EventResponse>builder()
                .message("Publish sự kiện thành công")
                .result(result)
                .build();
    }

    // ── PR06 ── Hủy sự kiện ──────────────────────────────────────────────────
    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<EventResponse> cancel(@PathVariable("id") UUID id) {
        EventResponse result = eventService.cancel(id);
        return ApiResponse.<EventResponse>builder()
                .message("Hủy sự kiện thành công")
                .result(result)
                .build();
    }

    // ── PR07 ── Danh sách sự kiện PUBLISHED (phân trang, công khai) ──────────
    @GetMapping
    public ApiResponse<PageResponse<EventSummaryResponse>> getPublishedList(
            @RequestParam(defaultValue = "0", name = "page") @Min(0) int page,
            @RequestParam(defaultValue = "20", name = "size") @Min(1) @Max(100) int size
    ) {
        PageResponse<EventSummaryResponse> result = eventService.getPublishedList(page, size);
        return ApiResponse.<PageResponse<EventSummaryResponse>>builder()
                .result(result)
                .build();
    }

    // ── PR08 ── Chi tiết sự kiện (PUBLISHED hoặc CANCELLED, công khai) ───────
    @GetMapping("/{id}")
    public ApiResponse<EventDetailResponse> getDetail(@PathVariable("id") UUID id) {
        EventDetailResponse result = eventService.getDetail(id);
        return ApiResponse.<EventDetailResponse>builder().result(result).build();
    }
}
