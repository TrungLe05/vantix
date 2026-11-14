package com.dev.order_service.repository;

import com.dev.order_service.entity.TicketInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TicketInventoryRepository extends JpaRepository<TicketInventory, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO ticket_inventory (ticket_type_id, event_id, total_quantity, held_quantity, sold_quantity, updated_at)
            VALUES (:ticketTypeId, :eventId, :totalQuantity, 0, 0, now())
            ON CONFLICT (ticket_type_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("eventId") UUID eventId,
            @Param("totalQuantity") int totalQuantity
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from TicketInventory i where i.ticketTypeId = :ticketTypeId")
    TicketInventory findForUpdate(@Param("ticketTypeId") UUID ticketTypeId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update TicketInventory i
            set i.heldQuantity = i.heldQuantity + :quantity, i.updatedAt = :now
            where i.ticketTypeId = :ticketTypeId
              and i.heldQuantity + i.soldQuantity + :quantity <= i.totalQuantity
            """)
    int tryHold(
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("quantity") int quantity,
            @Param("now") java.time.Instant now
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update TicketInventory i
            set i.heldQuantity = i.heldQuantity - :quantity, i.updatedAt = :now
            where i.ticketTypeId = :ticketTypeId and i.heldQuantity >= :quantity
            """)
    int releaseHold(
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("quantity") int quantity,
            @Param("now") java.time.Instant now
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update TicketInventory i
            set i.heldQuantity = i.heldQuantity - :quantity,
                i.soldQuantity = i.soldQuantity + :quantity,
                i.updatedAt = :now
            where i.ticketTypeId = :ticketTypeId and i.heldQuantity >= :quantity
            """)
    int confirmSale(
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("quantity") int quantity,
            @Param("now") java.time.Instant now
    );

    // Hoàn vé đã bán: trả số lượng từ sold về khả dụng
    @Modifying(flushAutomatically = true)
    @Query("""
            update TicketInventory i
            set i.soldQuantity = i.soldQuantity - :quantity, i.updatedAt = :now
            where i.ticketTypeId = :ticketTypeId and i.soldQuantity >= :quantity
            """)
    int releaseSold(
            @Param("ticketTypeId") UUID ticketTypeId,
            @Param("quantity") int quantity,
            @Param("now") java.time.Instant now
    );

    List<TicketInventory> findByEventId(UUID eventId);
}

