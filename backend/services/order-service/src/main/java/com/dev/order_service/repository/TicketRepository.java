package com.dev.order_service.repository;

import com.dev.order_service.entity.Ticket;
import com.dev.order_service.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    List<Ticket> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);

    Optional<Ticket> findByIdAndOrderId(UUID id, UUID orderId);

    Optional<Ticket> findByCode(String code);

    long countByOrderIdAndStatus(UUID orderId, TicketStatus status);

    /**
     * Check-in nguyên tử: chỉ một trong các lần quét đồng thời cùng mã đổi được VALID → CHECKED_IN.
     * Đây là lớp chặn quét trùng; đọc trạng thái trước rồi mới ghi sẽ cho hai cổng cùng qua.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Ticket t
            set t.status = com.dev.order_service.enums.TicketStatus.CHECKED_IN,
                t.checkedInAt = :now, t.checkedInBy = :staffId, t.updatedAt = :now
            where t.code = :code and t.eventId = :eventId
              and t.status = com.dev.order_service.enums.TicketStatus.VALID
            """)
    int checkIn(
            @Param("code") String code,
            @Param("eventId") UUID eventId,
            @Param("staffId") UUID staffId,
            @Param("now") Instant now
    );

    // Hoàn tiền / hủy event: vé chưa dùng không còn giá trị; vé đã check-in giữ nguyên làm lịch sử
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Ticket t
            set t.status = com.dev.order_service.enums.TicketStatus.VOID, t.updatedAt = :now
            where t.orderId = :orderId and t.status = com.dev.order_service.enums.TicketStatus.VALID
            """)
    int voidValidTickets(@Param("orderId") UUID orderId, @Param("now") Instant now);
}
