package com.dev.product_service.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class CreateTicketTypeRequest {

    @NotBlank
    private String category; // "SEATED" | "GENERAL_ADMISSION" — parse tay để tự kiểm soát lỗi, xem mục 5

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    @Digits(integer = 8, fraction = 2)
    private BigDecimal price;

    // Chỉ dùng khi category = GENERAL_ADMISSION
    private Integer totalQuantity;

    // Chỉ dùng khi category = SEATED
    private List<UUID> seatIds;
}