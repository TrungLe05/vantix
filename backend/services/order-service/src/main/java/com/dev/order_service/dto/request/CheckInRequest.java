package com.dev.order_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CheckInRequest {

    // Sự kiện đang soát vé: vé của sự kiện khác bị từ chối như vé không tồn tại
    @NotNull
    private UUID eventId;

    @NotBlank
    @Size(max = 64)
    private String code;
}
