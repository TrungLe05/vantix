package com.dev.product_service.repository;

import com.dev.product_service.entities.SeatMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SeatMapRepository extends JpaRepository<SeatMap, UUID> {
    Optional<SeatMap> findByEventId(UUID eventId);
    boolean existsByEventId(UUID eventId);
}