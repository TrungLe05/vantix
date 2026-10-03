package com.dev.product_service.services;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_api_response.exception.CommonErrorCode;
import com.dev.product_service.dto.request.CreateTicketTypeRequest;
import com.dev.product_service.dto.request.UpdateTicketTypeRequest;
import com.dev.product_service.dto.response.TicketTypeResponse;
import com.dev.product_service.entities.Event;
import com.dev.product_service.entities.Seat;
import com.dev.product_service.entities.TicketType;
import com.dev.product_service.enums.EventStatus;
import com.dev.product_service.enums.TicketCategory;
import com.dev.product_service.exception.ProductErrorCode;
import com.dev.product_service.repository.EventRepository;
import com.dev.product_service.repository.SeatMapRepository;
import com.dev.product_service.repository.SeatRepository;
import com.dev.product_service.repository.TicketTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketTypeServiceImpl implements TicketTypeService {

    private final EventRepository eventRepository;
    private final SeatMapRepository seatMapRepository;
    private final SeatRepository seatRepository;
    private final TicketTypeRepository ticketTypeRepository;

    @Override
    @Transactional
    public TicketTypeResponse create(UUID eventId, CreateTicketTypeRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new AppException(ProductErrorCode.EVENT_NOT_FOUND));

        if (event.getStatus() != EventStatus.DRAFT) {
            throw new AppException(ProductErrorCode.EVENT_NOT_IN_DRAFT);
        }

        TicketCategory category = parseCategory(request.getCategory());

        TicketType ticketType;
        if (category == TicketCategory.SEATED) {
            ticketType = createSeatedTicketType(event, request);
        } else {
            ticketType = createGaTicketType(event, request);
        }

        return buildResponse(ticketType);
    }

    private TicketType createSeatedTicketType(Event event, CreateTicketTypeRequest request) {
        if (request.getSeatIds() == null || request.getSeatIds().isEmpty()) {
            throw new AppException(ProductErrorCode.SEAT_IDS_REQUIRED_FOR_SEATED);
        }

        var seatMap = seatMapRepository.findByEventId(event.getId())
                .orElseThrow(() -> new AppException(ProductErrorCode.SEAT_MAP_NOT_FOUND));

        // 1 query lấy mọi seatId thuộc ĐÚNG seat map này — phần tử nào trong request không nằm trong
        // kết quả trả về nghĩa là: hoặc không tồn tại, hoặc thuộc event/seat map khác
        List<Seat> seats = seatRepository.findAllBySeatMapIdAndIdIn(seatMap.getId(), request.getSeatIds());

        if (seats.size() != request.getSeatIds().size()) {
            // Phân biệt 2 nguyên nhân: id hoàn toàn không tồn tại trong DB, hay tồn tại nhưng khác event
            boolean anyMissingEntirely = seats.size() < request.getSeatIds().size()
                    && seatRepository.findAllById(request.getSeatIds()).size() != request.getSeatIds().size();
            throw new AppException(anyMissingEntirely
                    ? ProductErrorCode.SEAT_NOT_FOUND
                    : ProductErrorCode.SEAT_BELONGS_TO_DIFFERENT_EVENT);
        }

        boolean alreadyAssigned = seats.stream().anyMatch(s -> s.getTicketTypeId() != null);
        if (alreadyAssigned) {
            throw new AppException(ProductErrorCode.SEAT_ALREADY_ASSIGNED);
        }

        TicketType ticketType = TicketType.builder()
                .eventId(event.getId())
                .category(TicketCategory.SEATED)
                .name(request.getName().trim())
                .price(request.getPrice())
                .totalQuantity(seats.size())
                .build();
        ticketType = ticketTypeRepository.save(ticketType);

        for (Seat seat : seats) {
            seat.setTicketTypeId(ticketType.getId());
        }
        seatRepository.saveAll(seats);

        return ticketType;
    }

    private TicketType createGaTicketType(Event event, CreateTicketTypeRequest request) {
        if (request.getTotalQuantity() == null || request.getTotalQuantity() <= 0) {
            throw new AppException(ProductErrorCode.GA_QUANTITY_REQUIRED);
        }

        TicketType ticketType = TicketType.builder()
                .eventId(event.getId())
                .category(TicketCategory.GENERAL_ADMISSION)
                .name(request.getName().trim())
                .price(request.getPrice())
                .totalQuantity(request.getTotalQuantity())
                .build();

        return ticketTypeRepository.save(ticketType);
    }

    private TicketCategory parseCategory(String raw) {
        try {
            return TicketCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    @Override
    @Transactional
    public TicketTypeResponse update(UUID ticketTypeId, UpdateTicketTypeRequest request) {
        TicketType ticketType = ticketTypeRepository.findById(ticketTypeId)
                .orElseThrow(() -> new AppException(ProductErrorCode.TICKET_TYPE_NOT_FOUND));

        Event event = eventRepository.findById(ticketType.getEventId())
                .orElseThrow(() -> new AppException(ProductErrorCode.EVENT_NOT_FOUND));

        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new AppException(ProductErrorCode.EVENT_ALREADY_CANCELLED);
        }

        if (request.getName() != null) {
            ticketType.setName(request.getName().trim());
        }
        if (request.getPrice() != null) {
            ticketType.setPrice(request.getPrice());
        }

        return buildResponse(ticketType);
    }

    private TicketTypeResponse buildResponse(TicketType ticketType){
        return TicketTypeResponse.builder()
                .id(ticketType.getId())
                .eventId(ticketType.getEventId())
                .category(ticketType.getCategory())
                .name(ticketType.getName())
                .price(ticketType.getPrice())
                .totalQuantity(ticketType.getTotalQuantity())
                .build();
    }
}