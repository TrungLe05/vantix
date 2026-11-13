import { apiClient } from "./client";
import type { ApiResponse } from "@/types/common";
import type { Order } from "@/types/orders";

export async function getOrders() {
  const response = await apiClient.get<ApiResponse<Order[]>>("/orders");
  return response.data.result ?? [];
}

export async function getAvailability(eventId: string) {
  const response = await apiClient.get<
    ApiResponse<{
      eventId: string;
      seats: Array<{
        seatId: string;
        status: "AVAILABLE" | "HELD" | "SOLD" | "UNAVAILABLE";
      }>;
      ticketTypes: Array<{
        ticketTypeId: string;
        category: string;
        totalQuantity: number;
        availableQuantity: number;
      }>;
    }>
  >(`/orders/events/${eventId}/availability`);
  if (!response.data.result)
    throw new Error("Could not retrieve ticket availability");
  return response.data.result;
}

export async function getOrder(orderId: string) {
  const response = await apiClient.get<ApiResponse<Order>>(
    `/orders/${orderId}`,
  );
  if (!response.data.result) throw new Error("Could not retrieve this cart");
  return response.data.result;
}

export async function createOrder(
  eventId: string,
  items: Array<{ ticketTypeId: string; seatId?: string; quantity: number }>,
) {
  const response = await apiClient.post<ApiResponse<Order>>(
    "/orders",
    { eventId, items },
    {
      headers: { "Idempotency-Key": crypto.randomUUID() },
    },
  );
  if (!response.data.result) throw new Error("Could not reserve these tickets");
  return response.data.result;
}

export async function removeOrderItem(orderId: string, itemId: string) {
  const response = await apiClient.delete<ApiResponse<Order>>(
    `/orders/${orderId}/items/${itemId}`,
  );
  if (!response.data.result) throw new Error("Could not remove this ticket");
  return response.data.result;
}

export async function updateOrderItemQuantity(
  orderId: string,
  itemId: string,
  quantity: number,
) {
  const response = await apiClient.patch<ApiResponse<Order>>(
    `/orders/${orderId}/items/${itemId}`,
    { quantity },
  );
  if (!response.data.result) throw new Error("Could not update ticket quantity");
  return response.data.result;
}

export async function confirmOrder(orderId: string) {
  const response = await apiClient.post<ApiResponse<Order>>(
    `/orders/${orderId}/confirm`,
    {},
    {
      headers: { "Idempotency-Key": crypto.randomUUID() },
    },
  );
  if (!response.data.result) throw new Error("Could not confirm this cart");
  return response.data.result;
}
