package com.dev.user_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OAuth2ExchangeRequest {

    @NotBlank
    @Size(max = 128)
    private String code;
}