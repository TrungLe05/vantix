package com.dev.order_service.entity;

import com.dev.order_service.enums.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
// Không đưa code vào toString: mã vé là thứ cho phép vào cổng
@ToString(onlyExplicitlyIncluded = true)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID orderItemId;

    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private UUID buyerId;

    @Column(nullable = false, updatable = false)
    private UUID ticketTypeId;

    private UUID seatId;

    @Column(nullable = false, length = 200)
    private String ticketNameSnapshot;

    @Column(length = 120)
    private String seatLabelSnapshot;

    @Column(nullable = false, length = 64, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    private Instant checkedInAt;

    private UUID checkedInBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
