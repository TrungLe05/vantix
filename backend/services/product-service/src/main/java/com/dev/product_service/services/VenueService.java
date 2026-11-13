package com.dev.product_service.services;

import com.dev.product_service.dto.request.CreateVenueRequest;
import com.dev.product_service.dto.response.VenueResponse;

import java.util.List;

public interface VenueService {
    VenueResponse create(CreateVenueRequest request);
    List<VenueResponse> getAll();
}