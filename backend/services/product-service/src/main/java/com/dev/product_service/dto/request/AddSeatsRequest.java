package com.dev.product_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AddSeatsRequest {

    @NotEmpty
    @Size(max = 2000)
    @Valid
    private List<SeatInput> seats;

    @Getter
    @Setter
    public static class SeatInput {

        @NotBlank
        @Size(max = 50)
        private String section;

        @NotBlank
        @Size(max = 20)
        private String rowLabel;

        @NotBlank
        @Size(max = 20)
        private String seatNumber;
    }
}