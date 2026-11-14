package com.dev.payment_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateCheckoutSessionRequest {

    @NotNull
    private UUID orderId;

    @NotNull
    private UUID buyerId;

    @NotNull
    private BigDecimal amount;

    @NotBlank
    private String currency;

    // Deadline hold của Order: quá mốc này Payment tự expire phiên thanh toán
    @NotNull
    private Instant expiresAt;
}
