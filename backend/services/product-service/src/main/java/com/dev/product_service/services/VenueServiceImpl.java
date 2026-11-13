package com.dev.product_service.services;

import com.dev.product_service.dto.request.CreateVenueRequest;
import com.dev.product_service.dto.response.VenueResponse;
import com.dev.product_service.entities.Venue;
import com.dev.product_service.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VenueServiceImpl implements VenueService {

    private final VenueRepository venueRepository;

    @Override
    @Transactional
    public VenueResponse create(CreateVenueRequest request) {
        Venue venue = Venue.builder()
                .name(request.getName().trim())
                .address(request.getAddress().trim())
                .city(request.getCity().trim())
                .build();
        return buildResponse(venueRepository.save(venue));
    }

    @Override
    public List<VenueResponse> getAll() {
        return venueRepository.findAll().stream().map(this::buildResponse).toList();
    }


    private VenueResponse buildResponse(Venue venue){
        return VenueResponse.builder()
                .id(venue.getId())
                .city(venue.getCity())
                .name(venue.getName())
                .address(venue.getAddress())
                .build();
    }
}