package com.dev.product_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.product_service.dto.request.AddSeatsRequest;
import com.dev.product_service.dto.request.CreateSeatMapRequest;
import com.dev.product_service.dto.response.AddSeatsResponse;
import com.dev.product_service.dto.response.SeatMapDetailResponse;
import com.dev.product_service.dto.response.SeatMapResponse;
import com.dev.product_service.services.SeatMapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/events/{eventId}/seat-map")
@RequiredArgsConstructor
public class SeatMapController {

    private final SeatMapService seatMapService;

    // ── PR09 ── Tạo sơ đồ ghế ────────────────────────────────────────────────
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SeatMapResponse>> create(
            @PathVariable("eventId") UUID eventId, @Valid @RequestBody CreateSeatMapRequest request
    ) {
        SeatMapResponse result = seatMapService.create(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<SeatMapResponse>builder().result(result).build());
    }

    // ── PR10 ── Thêm ghế hàng loạt ───────────────────────────────────────────
    @PostMapping("/seats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AddSeatsResponse>> addSeats(
            @PathVariable("eventId") UUID eventId, @Valid @RequestBody AddSeatsRequest request
    ) {
        AddSeatsResponse result = seatMapService.addSeats(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<AddSeatsResponse>builder()
                        .message("Đã thêm " + result.getCreatedCount() + " ghế")
                        .result(result)
                        .build());
    }

    // ── PR11 ── Xem sơ đồ ghế + hạng vé (công khai) ─────────────────────────
    @GetMapping
    public ResponseEntity<ApiResponse<SeatMapDetailResponse>> getSeatMapDetail(
            @PathVariable("eventId") UUID eventId
    ) {
        SeatMapDetailResponse result = seatMapService.getSeatMapDetail(eventId);
        return ResponseEntity.ok(ApiResponse.<SeatMapDetailResponse>builder().result(result).build());
    }
}