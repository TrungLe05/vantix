package com.dev.product_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.product_service.dto.request.CreateVenueRequest;
import com.dev.product_service.dto.response.VenueResponse;
import com.dev.product_service.services.VenueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<VenueResponse>> create(@Valid @RequestBody CreateVenueRequest request) {
        VenueResponse result = venueService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<VenueResponse>builder().result(result).build());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<VenueResponse>>> getAll() {
        return ResponseEntity.ok(ApiResponse.<List<VenueResponse>>builder().result(venueService.getAll()).build());
    }
}