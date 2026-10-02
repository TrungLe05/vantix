package com.dev.product_service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class UpdateTicketTypeRequest {

    @Size(max = 200)
    private String name;

    @DecimalMin(value = "0.0", inclusive = true)
    @Digits(integer = 8, fraction = 2)
    private BigDecimal price;
}