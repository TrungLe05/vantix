import { apiClient } from "./client";
import type { ApiResponse } from "@/types/common";
import type { EventDetail } from "@/types/events";

export interface AdminEvent {
  id: string;
  venueId: string;
  name: string;
  description: string | null;
  startAt: string;
  endAt: string;
  salesStartAt: string | null;
  salesEndAt: string | null;
  status: "DRAFT" | "PUBLISHED" | "CANCELLED";
  bannerImageUrl: string | null;
  createdAt: string;
}

export interface CreateAdminEventRequest {
  venueId: string;
  name: string;
  description?: string;
  startAt: string;
  endAt: string;
  salesStartAt?: string;
  salesEndAt?: string;
  bannerImageUrl?: string;
}

export interface CreateTicketTypeRequest {
  category: "SEATED" | "GENERAL_ADMISSION";
  name: string;
  price: number;
  totalQuantity?: number;
  seatIds?: string[];
}

export type UpdateAdminEventRequest = Partial<CreateAdminEventRequest>;

export interface AdminSeat {
  id: string;
  section: string;
  rowLabel: string;
  seatNumber: string;
  displayLabel: string;
}

export async function createAdminEvent(request: CreateAdminEventRequest) {
  const response = await apiClient.post<ApiResponse<AdminEvent>>("/events", request);
  if (!response.data.result) throw new Error("Could not retrieve the created event");
  return response.data.result;
}

export async function createSeatMap(eventId: string, name: string) {
  const response = await apiClient.post<ApiResponse<{ id: string }>>(
    `/events/${eventId}/seat-map`, { name },
  );
  if (!response.data.result) throw new Error("Could not retrieve the created seat map");
  return response.data.result;
}

export async function addSeats(eventId: string, seats: Array<{
  section: string; rowLabel: string; seatNumber: string;
}>) {
  const response = await apiClient.post<ApiResponse<{ createdCount: number; seats: AdminSeat[] }>>(
    `/events/${eventId}/seat-map/seats`, { seats },
  );
  if (!response.data.result) throw new Error("Could not retrieve the created seats");
  return response.data.result;
}

export async function createTicketType(eventId: string, request: CreateTicketTypeRequest) {
  const response = await apiClient.post<ApiResponse<{ id: string; totalQuantity: number }>>(
    `/events/${eventId}/ticket-types`, request,
  );
  if (!response.data.result) throw new Error("Could not retrieve the created ticket type");
  return response.data.result;
}

export async function publishAdminEvent(eventId: string) {
  const response = await apiClient.patch<ApiResponse<AdminEvent>>(`/events/${eventId}/publish`);
  if (!response.data.result) throw new Error("Could not retrieve the event status");
  return response.data.result;
}

export async function updateAdminEvent(eventId: string, request: UpdateAdminEventRequest) {
  const response = await apiClient.patch<ApiResponse<AdminEvent>>(`/events/${eventId}`, request);
  if (!response.data.result) throw new Error("Could not retrieve the updated event");
  return response.data.result;
}

export async function getAdminEvent(eventId: string) {
  const response = await apiClient.get<ApiResponse<EventDetail>>(`/events/${eventId}`);
  if (!response.data.result) throw new Error("Could not retrieve event details");
  return response.data.result;
}

export async function cancelAdminEvent(eventId: string) {
  const response = await apiClient.patch<ApiResponse<AdminEvent>>(`/events/${eventId}/cancel`);
  if (!response.data.result) throw new Error("Could not retrieve the event status");
  return response.data.result;
}
