package com.dev.product_service.repository;

import com.dev.product_service.entities.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {
    List<Seat> findBySeatMapIdOrderBySectionAscRowLabelAscSeatNumberAsc(UUID seatMapId);
    List<Seat> findAllByIdIn(List<UUID> ids);
    boolean existsBySeatMapIdAndTicketTypeIdIsNull(UUID seatMapId);
    List<Seat> findAllBySeatMapIdAndIdIn(UUID seatMapId, List<UUID> ids);
}