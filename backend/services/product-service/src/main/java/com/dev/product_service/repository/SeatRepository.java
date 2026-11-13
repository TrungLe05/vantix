package com.dev.product_service.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import com.dev.product_service.entities.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {
    List<Seat> findBySeatMapIdOrderBySectionAscRowLabelAscSeatNumberAsc(UUID seatMapId);
    List<Seat> findAllByIdIn(List<UUID> ids);
    boolean existsBySeatMapIdAndTicketTypeIdIsNull(UUID seatMapId);
    List<Seat> findAllBySeatMapIdAndIdIn(UUID seatMapId, List<UUID> ids);

    // Gán hạng vé NGUYÊN TỬ (PR12): chỉ gán ghế còn trống của đúng seat map. Hai request song song
    // cùng nhắm một ghế thì request sau chờ khóa dòng, đọc lại điều kiện và cập nhật 0 dòng.
    // flushAutomatically: INSERT ticket_types phải xuống DB trước (FK seats.ticket_type_id)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Seat s SET s.ticketTypeId = :ticketTypeId " +
            "WHERE s.seatMapId = :seatMapId AND s.id IN :ids AND s.ticketTypeId IS NULL")
    int assignTicketTypeIfUnassigned(@Param("ticketTypeId") UUID ticketTypeId,
                                     @Param("seatMapId") UUID seatMapId,
                                     @Param("ids") List<UUID> ids);
}
