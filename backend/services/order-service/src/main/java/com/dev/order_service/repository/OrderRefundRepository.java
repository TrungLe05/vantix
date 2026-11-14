package com.dev.order_service.repository;

import com.dev.order_service.entity.OrderRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRefundRepository extends JpaRepository<OrderRefund, UUID> {

    Optional<OrderRefund> findByOrderId(UUID orderId);
}
