package com.dev.product_service.services;

import com.dev.product_service.dto.request.AddSeatsRequest;
import com.dev.product_service.dto.request.CreateSeatMapRequest;
import com.dev.product_service.dto.response.AddSeatsResponse;
import com.dev.product_service.dto.response.SeatMapDetailResponse;
import com.dev.product_service.dto.response.SeatMapResponse;

import java.util.UUID;

public interface SeatMapService {
    SeatMapResponse create(UUID eventId, CreateSeatMapRequest request);
    AddSeatsResponse addSeats(UUID eventId, AddSeatsRequest request);

    /** PR11 — Xem sơ đồ ghế kèm hạng vé từng ghế (public). */
    SeatMapDetailResponse getSeatMapDetail(UUID eventId);
}