package com.dev.product_service.services;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_kafka.event.EventCancelledEvent;
import com.dev.commonlib_kafka.topic.KafkaTopics;
import com.dev.product_service.dto.request.CreateEventRequest;
import com.dev.product_service.dto.request.UpdateEventRequest;
import com.dev.product_service.dto.response.*;
import com.dev.product_service.entities.Event;
import com.dev.product_service.entities.TicketType;
import com.dev.product_service.entities.Venue;
import com.dev.product_service.enums.EventStatus;
import com.dev.product_service.exception.ProductErrorCode;
import com.dev.product_service.repository.EventRepository;
import com.dev.product_service.repository.SeatMapRepository;
import com.dev.product_service.repository.SeatRepository;
import com.dev.product_service.repository.TicketTypeRepository;
import com.dev.product_service.repository.VenueRepository;
import com.dev.product_service.services.EventService;
import com.dev.product_service.validation.EventTimeValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final SeatMapRepository seatMapRepository;
    private final SeatRepository seatRepository;
    private final EventTimeValidator timeValidator;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ─────────────────────────────────────────────────────────────────────────
    // PR03 — Tạo sự kiện
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public EventResponse create(CreateEventRequest request) {
        if (!venueRepository.existsById(request.getVenueId())) {
            throw new AppException(ProductErrorCode.VENUE_NOT_FOUND);
        }

        timeValidator.validateEventWindow(request.getStartAt(), request.getEndAt());
        timeValidator.validateSalesWindow(request.getSalesStartAt(), request.getSalesEndAt(), request.getStartAt());

        Event event = Event.builder()
                .venueId(request.getVenueId())
                .name(request.getName().trim())
                .description(request.getDescription())
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .salesStartAt(request.getSalesStartAt())
                .salesEndAt(request.getSalesEndAt())
                .status(EventStatus.DRAFT)
                .bannerImageUrl(request.getBannerImageUrl())
                .build();

        return buildResponse(eventRepository.save(event));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PR04 — Sửa sự kiện
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public EventResponse update(UUID eventId, UpdateEventRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new AppException(ProductErrorCode.EVENT_NOT_FOUND));

        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new AppException(ProductErrorCode.EVENT_ALREADY_CANCELLED);
        }

        boolean touchesRestrictedField = request.getVenueId() != null
                || request.getStartAt() != null
                || request.getEndAt() != null
                || request.getSalesStartAt() != null
                || request.getSalesEndAt() != null;

        if (touchesRestrictedField && event.getStatus() != EventStatus.DRAFT) {
            throw new AppException(ProductErrorCode.EVENT_NOT_IN_DRAFT);
        }

        if (request.getName() != null) {
            event.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            event.setDescription(request.getDescription());
        }
        if (request.getBannerImageUrl() != null) {
            event.setBannerImageUrl(request.getBannerImageUrl());
        }

        if (request.getVenueId() != null) {
            if (!venueRepository.existsById(request.getVenueId())) {
                throw new AppException(ProductErrorCode.VENUE_NOT_FOUND);
            }
            event.setVenueId(request.getVenueId());
        }
        if (request.getStartAt() != null) {
            event.setStartAt(request.getStartAt());
        }
        if (request.getEndAt() != null) {
            event.setEndAt(request.getEndAt());
        }
        if (request.getSalesStartAt() != null) {
            event.setSalesStartAt(request.getSalesStartAt());
        }
        if (request.getSalesEndAt() != null) {
            event.setSalesEndAt(request.getSalesEndAt());
        }

        // Validate lại TOÀN BỘ bộ ba thời gian sau khi áp giá trị mới — đúng lưu ý ở API design (bước 4 của PR04):
        // không chỉ validate field vừa gửi một mình, vì startAt đổi có thể làm sales window cũ trở nên vô nghĩa
        if (touchesRestrictedField) {
            timeValidator.validateEventWindow(event.getStartAt(), event.getEndAt());
            timeValidator.validateSalesWindow(event.getSalesStartAt(), event.getSalesEndAt(), event.getStartAt());
        }

        return buildResponse(event);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PR05 — Publish sự kiện (DRAFT → PUBLISHED)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public EventResponse publish(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new AppException(ProductErrorCode.EVENT_NOT_FOUND));

        if (event.getStatus() == EventStatus.PUBLISHED) {
            throw new AppException(ProductErrorCode.EVENT_ALREADY_PUBLISHED);
        }
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new AppException(ProductErrorCode.EVENT_ALREADY_CANCELLED);
        }

        // Gom toàn bộ lý do không đạt điều kiện publish — không dừng ở lý do đầu tiên
        // để ADMIN thấy hết trong một lần gọi (theo P2_product_api_design.md bước 2 PR05)
        List<String> reasons = validatePublishConditions(event);
        if (!reasons.isEmpty()) {
            throw new AppException(ProductErrorCode.EVENT_PUBLISH_VALIDATION_FAILED,
                    new PublishValidationResult(reasons));
        }

        event.setStatus(EventStatus.PUBLISHED);
        return buildResponse(event);
    }

    /**
     * Gom toàn bộ lý do không đạt điều kiện publish.
     * Trả danh sách rỗng nếu event đủ điều kiện publish.
     */
    private List<String> validatePublishConditions(Event event) {
        List<String> reasons = new ArrayList<>();

        List<TicketType> ticketTypes = ticketTypeRepository.findByEventId(event.getId());

        if (ticketTypes.isEmpty()) {
            reasons.add("Chưa có hạng vé nào");
        } else {
            // Kiểm tra SEATED có ghế không
            ticketTypes.stream()
                    .filter(tt -> tt.getCategory().name().equals("SEATED") && tt.getTotalQuantity() == 0)
                    .forEach(tt -> reasons.add("Hạng vé SEATED \"" + tt.getName() + "\" chưa có ghế nào"));

            // Kiểm tra GA có số lượng hợp lệ không
            ticketTypes.stream()
                    .filter(tt -> tt.getCategory().name().equals("GENERAL_ADMISSION") && tt.getTotalQuantity() <= 0)
                    .forEach(tt -> reasons.add("Hạng vé GA \"" + tt.getName() + "\" phải có số lượng > 0"));
        }

        // Kiểm tra còn ghế nào chưa gán hạng vé không (chỉ khi có seat map)
        seatMapRepository.findByEventId(event.getId()).ifPresent(seatMap -> {
            long unassignedCount = seatRepository
                    .findBySeatMapIdOrderBySectionAscRowLabelAscSeatNumberAsc(seatMap.getId())
                    .stream()
                    .filter(s -> s.getTicketTypeId() == null)
                    .count();
            if (unassignedCount > 0) {
                reasons.add("Còn " + unassignedCount + " ghế chưa được gán hạng vé");
            }
        });

        return reasons;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PR06 — Hủy sự kiện → publish Kafka product.event-cancelled
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public EventResponse cancel(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new AppException(ProductErrorCode.EVENT_NOT_FOUND));

        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new AppException(ProductErrorCode.EVENT_ALREADY_CANCELLED);
        }

        // Cho phép hủy từ cả DRAFT và PUBLISHED
        Instant cancelledAt = Instant.now();
        event.setStatus(EventStatus.CANCELLED);
        EventResponse response = buildResponse(eventRepository.save(event));

        // Publish Kafka SAU KHI transaction commit — lỗi publish chỉ log, không rollback,
        // không trả lỗi cho ADMIN (theo P2_product_api_design.md mục 4 điểm 3).
        // Ở P2 chưa có consumer, topic này sẽ được order-service consume ở P4.
        kafkaTemplate.send(
                KafkaTopics.PRODUCT_EVENT_CANCELLED,
                event.getId().toString(), // key = eventId: đảm bảo ordering nếu có nhiều event
                new EventCancelledEvent(event.getId(), cancelledAt)
        ).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Publish product.event-cancelled thất bại, eventId={}", event.getId(), ex);
            }
        });

        return response;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PR07 — Danh sách PUBLISHED phân trang
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EventSummaryResponse> getPublishedList(int page, int size) {
        Instant now = Instant.now();
        Page<Event> eventPage = eventRepository
                .findByStatusAndStartAtGreaterThanEqualOrderByStartAtAsc(
                        EventStatus.PUBLISHED, now, PageRequest.of(page, size)
                );

        List<EventSummaryResponse> content = eventPage.getContent().stream()
                .map(this::buildSummaryResponse)
                .toList();

        return PageResponse.<EventSummaryResponse>builder()
                .content(content)
                .page(eventPage.getNumber())
                .size(eventPage.getSize())
                .totalElements(eventPage.getTotalElements())
                .totalPages(eventPage.getTotalPages())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PR08 — Chi tiết sự kiện (ẩn DRAFT)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public EventDetailResponse getDetail(UUID eventId) {
        // findByIdAndStatusNot(DRAFT): event không tồn tại VÀ event đang DRAFT đều trả cùng lỗi 3101
        // — không để lộ sự tồn tại của event chưa publish (mục 4 điểm 6 của API design)
        Event event = eventRepository.findByIdAndStatusNot(eventId, EventStatus.DRAFT)
                .orElseThrow(() -> new AppException(ProductErrorCode.EVENT_NOT_FOUND));

        Venue venue = venueRepository.findById(event.getVenueId())
                .orElseThrow(() -> new AppException(ProductErrorCode.VENUE_NOT_FOUND));

        List<TicketType> ticketTypes = ticketTypeRepository.findByEventId(event.getId());
        boolean hasSeatMap = seatMapRepository.existsByEventId(event.getId());

        return EventDetailResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .startAt(event.getStartAt())
                .endAt(event.getEndAt())
                .salesStartAt(event.getSalesStartAt())
                .salesEndAt(event.getSalesEndAt())
                .status(event.getStatus().name())
                .bannerImageUrl(event.getBannerImageUrl())
                .createdAt(event.getCreatedAt())
                .venue(EventDetailResponse.VenueDetail.builder()
                        .id(venue.getId())
                        .name(venue.getName())
                        .address(venue.getAddress())
                        .city(venue.getCity())
                        .build())
                .ticketTypes(ticketTypes.stream()
                        .map(tt -> EventDetailResponse.TicketTypeSummary.builder()
                                .id(tt.getId())
                                .category(tt.getCategory().name())
                                .name(tt.getName())
                                .price(tt.getPrice())
                                .totalQuantity(tt.getTotalQuantity())
                                .build())
                        .toList())
                .hasSeatMap(hasSeatMap)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private EventResponse buildResponse(Event event) {
        return EventResponse.builder()
                .id(event.getId())
                .venueId(event.getVenueId())
                .name(event.getName())
                .description(event.getDescription())
                .startAt(event.getStartAt())
                .endAt(event.getEndAt())
                .salesStartAt(event.getSalesStartAt())
                .salesEndAt(event.getSalesEndAt())
                .status(event.getStatus())
                .bannerImageUrl(event.getBannerImageUrl())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private EventSummaryResponse buildSummaryResponse(Event event) {
        Venue venue = venueRepository.findById(event.getVenueId()).orElse(null);

        return EventSummaryResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .startAt(event.getStartAt())
                .endAt(event.getEndAt())
                .status(event.getStatus().name())
                .bannerImageUrl(event.getBannerImageUrl())
                .minPrice(eventRepository.findMinPriceByEventId(event.getId()))
                .venue(venue == null ? null : EventSummaryResponse.VenueSummary.builder()
                        .id(venue.getId())
                        .name(venue.getName())
                        .city(venue.getCity())
                        .build())
                .build();
    }
}
