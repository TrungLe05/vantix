package com.dev.order_service.repository;

import com.dev.order_service.entity.BotFlag;
import com.dev.order_service.enums.BotFlagStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface BotFlagRepository extends JpaRepository<BotFlag, UUID> {

    boolean existsByStatusAndBuyerId(BotFlagStatus status, UUID buyerId);

    boolean existsByStatusAndDeviceHash(BotFlagStatus status, String deviceHash);

    Page<BotFlag> findByStatusOrderByLastFlaggedAtDescIdAsc(BotFlagStatus status, Pageable pageable);

    Page<BotFlag> findAllByOrderByLastFlaggedAtDescIdAsc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM BotFlag f WHERE f.id = :id")
    Optional<BotFlag> findByIdForUpdate(@Param("id") UUID id);
}
