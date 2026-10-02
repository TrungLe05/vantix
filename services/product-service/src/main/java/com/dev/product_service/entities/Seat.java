package com.dev.product_service.entities;

import com.dev.product_service.enums.SeatStatus;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "seats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Seat {

    @Id
    @GeneratedValue
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @Column(nullable = false)
    private UUID seatMapId;

    private UUID ticketTypeId; // nullable: chưa gán giá lúc mới tạo ghế

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private String rowLabel;

    @Column(nullable = false)
    private String seatNumber;

    @ToString.Include
    @Column(nullable = false)
    private String displayLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SeatStatus status = SeatStatus.ACTIVE;
}