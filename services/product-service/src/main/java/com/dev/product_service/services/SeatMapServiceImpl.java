package com.dev.product_service.services;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.product_service.dto.request.AddSeatsRequest;
import com.dev.product_service.dto.request.CreateSeatMapRequest;
import com.dev.product_service.dto.response.AddSeatsResponse;
import com.dev.product_service.dto.response.SeatMapDetailResponse;
import com.dev.product_service.dto.response.SeatMapResponse;
import com.dev.product_service.dto.response.SeatResponse;
import com.dev.product_service.entities.Event;
import com.dev.product_service.entities.Seat;
import com.dev.product_service.entities.SeatMap;
import com.dev.product_service.entities.TicketType;
import com.dev.product_service.enums.EventStatus;
import com.dev.product_service.exception.ProductErrorCode;
import com.dev.product_service.repository.EventRepository;
import com.dev.product_service.repository.SeatMapRepository;
import com.dev.product_service.repository.SeatRepository;
import com.dev.product_service.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeatMapServiceImpl implements SeatMapService {

    private final EventRepository eventRepository;
    private final SeatMapRepository seatMapRepository;
    private final SeatRepository seatRepository;
    private final TicketTypeRepository ticketTypeRepository;

    @Override
    @Transactional
    public SeatMapResponse create(UUID eventId, CreateSeatMapRequest request) {
        Event event = loadDraftEvent(eventId);

        if (seatMapRepository.existsByEventId(event.getId())) {
            throw new AppException(ProductErrorCode.SEAT_MAP_ALREADY_EXISTS);
        }

        SeatMap seatMap = SeatMap.builder()
                .eventId(event.getId())
                .name(request.getName().trim())
                .build();

        return buildResponse(seatMapRepository.save(seatMap));
    }

    @Override
    @Transactional
    public AddSeatsResponse addSeats(UUID eventId, AddSeatsRequest request) {
        Event event = loadDraftEvent(eventId);

        SeatMap seatMap = seatMapRepository.findByEventId(event.getId())
                .orElseThrow(() -> new AppException(ProductErrorCode.SEAT_MAP_NOT_FOUND));

        // Chặn trùng NGAY TRONG payload trước khi chạm DB — lỗi rõ ràng hơn là để DB ném unique violation
        Set<String> seenKeys = new HashSet<>();
        for (AddSeatsRequest.SeatInput input : request.getSeats()) {
            String key = input.getSection() + "|" + input.getRowLabel() + "|" + input.getSeatNumber();
            if (!seenKeys.add(key)) {
                throw new AppException(ProductErrorCode.DUPLICATE_SEAT);
            }
        }

        List<Seat> seats = new ArrayList<>();
        for (AddSeatsRequest.SeatInput input : request.getSeats()) {
            seats.add(Seat.builder()
                    .seatMapId(seatMap.getId())
                    .section(input.getSection().trim())
                    .rowLabel(input.getRowLabel().trim())
                    .seatNumber(input.getSeatNumber().trim())
                    .displayLabel(input.getRowLabel().trim() + "-" + input.getSeatNumber().trim())
                    .build());
        }

        List<Seat> saved;
        try {
            // saveAll trong cùng transaction — lỗi ở bất kỳ dòng nào rollback toàn bộ batch (tất-cả-hoặc-không-gì)
            saved = seatRepository.saveAll(seats);
        } catch (DataIntegrityViolationException e) {
            // Lớp chặn cuối: trùng với ghế đã tồn tại từ trước (không nằm trong chính payload này)
            throw new AppException(ProductErrorCode.DUPLICATE_SEAT);
        }

        return AddSeatsResponse.builder()
                .createdCount(saved.size())
                .seats(saved.stream().map(this::buildResponse).toList())
                .build();
    }

    private Event loadDraftEvent(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new AppException(ProductErrorCode.EVENT_NOT_FOUND));

        if (event.getStatus() != EventStatus.DRAFT) {
            throw new AppException(ProductErrorCode.EVENT_NOT_IN_DRAFT);
        }

        return event;
    }

    private SeatResponse buildResponse(Seat seat){
        return SeatResponse.builder()
                .id(seat.getId())
                .section(seat.getSection())
                .rowLabel(seat.getRowLabel())
                .seatNumber(seat.getSeatNumber())
                .displayLabel(seat.getDisplayLabel())
                .build();
    }

    public SeatMapResponse buildResponse(SeatMap seatMap) {
        return SeatMapResponse.builder()
                .id(seatMap.getId())
                .eventId(seatMap.getEventId())
                .name(seatMap.getName())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PR11 — Xem sơ đồ ghế + hạng vé (public)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public SeatMapDetailResponse getSeatMapDetail(UUID eventId) {
        // Áp dụng cùng quy tắc ẩn DRAFT với PR08: event DRAFT trả cùng lỗi 3101 với không tồn tại
        Event event = eventRepository.findByIdAndStatusNot(eventId, EventStatus.DRAFT)
                .orElseThrow(() -> new AppException(ProductErrorCode.EVENT_NOT_FOUND));

        SeatMap seatMap = seatMapRepository.findByEventId(event.getId())
                .orElseThrow(() -> new AppException(ProductErrorCode.SEAT_MAP_NOT_FOUND));

        List<Seat> seats = seatRepository
                .findBySeatMapIdOrderBySectionAscRowLabelAscSeatNumberAsc(seatMap.getId());

        // Tải tất cả TicketType liên quan trong 1 query — tránh N+1
        List<UUID> ticketTypeIds = seats.stream()
                .map(Seat::getTicketTypeId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<UUID, TicketType> ticketTypeMap = ticketTypeIds.isEmpty()
                ? Map.of()
                : ticketTypeRepository.findAllById(ticketTypeIds).stream()
                        .collect(Collectors.toMap(TicketType::getId, tt -> tt));

        List<SeatMapDetailResponse.SeatDetail> seatDetails = seats.stream()
                .map(seat -> {
                    TicketType tt = seat.getTicketTypeId() != null
                            ? ticketTypeMap.get(seat.getTicketTypeId())
                            : null;
                    return SeatMapDetailResponse.SeatDetail.builder()
                            .id(seat.getId())
                            .section(seat.getSection())
                            .rowLabel(seat.getRowLabel())
                            .seatNumber(seat.getSeatNumber())
                            .displayLabel(seat.getDisplayLabel())
                            .ticketType(tt == null ? null : SeatMapDetailResponse.TicketTypeBrief.builder()
                                    .id(tt.getId())
                                    .name(tt.getName())
                                    .price(tt.getPrice())
                                    .build())
                            .build();
                })
                .toList();

        return SeatMapDetailResponse.builder()
                .seatMapId(seatMap.getId())
                .seats(seatDetails)
                .build();
    }
}