package com.dev.order_service.repository;

import com.dev.order_service.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {
    Optional<IdempotencyRecord> findByBuyerIdAndOperationAndIdempotencyKey(
            UUID buyerId, String operation, String idempotencyKey
    );
}
