package com.dev.product_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.product_service.dto.request.CreateTicketTypeRequest;
import com.dev.product_service.dto.request.UpdateTicketTypeRequest;
import com.dev.product_service.dto.response.TicketTypeResponse;
import com.dev.product_service.services.TicketTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TicketTypeController {

    private final TicketTypeService ticketTypeService;

    @PostMapping("/events/{eventId}/ticket-types")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TicketTypeResponse>> create(
            @PathVariable("eventId") UUID eventId, @Valid @RequestBody CreateTicketTypeRequest request
    ) {
        TicketTypeResponse result = ticketTypeService.create(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<TicketTypeResponse>builder().result(result).build());
    }

    @PatchMapping("/ticket-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TicketTypeResponse>> update(
            @PathVariable("id") UUID id, @Valid @RequestBody UpdateTicketTypeRequest request
    ) {
        TicketTypeResponse result = ticketTypeService.update(id, request);
        return ResponseEntity.ok(ApiResponse.<TicketTypeResponse>builder().result(result).build());
    }
}