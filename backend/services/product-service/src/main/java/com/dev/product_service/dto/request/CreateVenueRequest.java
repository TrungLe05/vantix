package com.dev.product_service.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateVenueRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotBlank
    @Size(max = 255)
    private String address;

    @NotBlank
    @Size(max = 100)
    private String city;

    // D45: tọa độ lấy từ kết quả tìm địa chỉ ở frontend (OpenStreetMap); bắt buộc với venue mới, lưu làm tròn 6 chữ số (~0.1 m)
    @NotNull
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    @Digits(integer = 3, fraction = 12)
    private java.math.BigDecimal latitude;

    @NotNull
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    @Digits(integer = 3, fraction = 12)
    private java.math.BigDecimal longitude;
}