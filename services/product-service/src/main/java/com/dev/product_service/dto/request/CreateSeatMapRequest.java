package com.dev.product_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSeatMapRequest {

    @NotBlank
    @Size(max = 200)
    private String name;
}