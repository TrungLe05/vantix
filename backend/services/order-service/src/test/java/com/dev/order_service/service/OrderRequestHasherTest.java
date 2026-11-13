package com.dev.order_service.service;

import com.dev.order_service.dto.request.OrderItemRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrderRequestHasherTest {

    private final OrderRequestHasher hasher = new OrderRequestHasher();

    @Test
    void sameRequestProducesSameHash() {
        UUID eventId = UUID.randomUUID();
        UUID ticketTypeId = UUID.randomUUID();
        List<OrderItemRequest> items = List.of(new OrderItemRequest(ticketTypeId, null, 2));

        assertEquals(hasher.hash(eventId, null, items), hasher.hash(eventId, null, items));
    }

    @Test
    void differentQuantitiesProduceDifferentHashes() {
        UUID eventId = UUID.randomUUID();
        UUID ticketTypeId = UUID.randomUUID();

        assertNotEquals(
                hasher.hash(eventId, null, List.of(new OrderItemRequest(ticketTypeId, null, 1))),
                hasher.hash(eventId, null, List.of(new OrderItemRequest(ticketTypeId, null, 2)))
        );
    }
}
