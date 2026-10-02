package com.dev.product_service.services;

import com.dev.product_service.dto.request.CreateTicketTypeRequest;
import com.dev.product_service.dto.request.UpdateTicketTypeRequest;
import com.dev.product_service.dto.response.TicketTypeResponse;

import java.util.UUID;

public interface TicketTypeService {
    TicketTypeResponse create(UUID eventId, CreateTicketTypeRequest request);
    TicketTypeResponse update(UUID ticketTypeId, UpdateTicketTypeRequest request);
}