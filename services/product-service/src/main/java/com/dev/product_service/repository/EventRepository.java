package com.dev.product_service.repository;

import com.dev.product_service.entities.Event;
import com.dev.product_service.enums.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    /**
     * PR07: Danh sách PUBLISHED còn chưa diễn ra, sắp theo startAt tăng dần.
     * Điều kiện startAt >= now lọc sự kiện đã diễn ra ra khỏi danh sách.
     */
    Page<Event> findByStatusAndStartAtGreaterThanEqualOrderByStartAtAsc(
            EventStatus status, Instant now, Pageable pageable
    );

    /**
     * PR08: Tìm event theo id khi status KHÔNG phải DRAFT — dùng để ẩn event DRAFT
     * khỏi API công khai (trả cùng lỗi 3101 EVENT_NOT_FOUND dù id đúng hay event đang DRAFT).
     */
    Optional<Event> findByIdAndStatusNot(UUID id, EventStatus status);

    /**
     * PR07: Lấy giá thấp nhất của các TicketType trong một event — dùng cho field minPrice.
     */
    @Query("SELECT MIN(t.price) FROM TicketType t WHERE t.eventId = :eventId")
    java.math.BigDecimal findMinPriceByEventId(@Param("eventId") UUID eventId);
}