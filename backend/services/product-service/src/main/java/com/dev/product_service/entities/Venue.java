package com.dev.product_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "venues")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Venue {

    @Id
    @GeneratedValue
    @EqualsAndHashCode.Include
    @ToString.Include
    private UUID id;

    @ToString.Include
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    // D45: NULL với venue tạo trước khi bắt buộc tọa độ
    @Column(precision = 9, scale = 6)
    private java.math.BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private java.math.BigDecimal longitude;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}