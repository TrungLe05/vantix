import { apiClient } from "./client";
import type { ApiResponse, PaginatedResponse } from "@/types/common";
import type { EventDetail, EventSummary, SeatMap } from "@/types/events";

export async function getEvents(page = 0, size = 20) {
  const response = await apiClient.get<
    ApiResponse<PaginatedResponse<EventSummary>>
  >("/events", {
    params: { page, size },
  });
  if (!response.data.result)
    throw new Error("Không nhận được danh sách sự kiện");
  return response.data.result;
}

export async function getEvent(eventId: string) {
  const response = await apiClient.get<ApiResponse<EventDetail>>(
    `/events/${eventId}`,
  );
  if (!response.data.result)
    throw new Error("Không nhận được thông tin sự kiện");
  return response.data.result;
}

export async function getSeatMap(eventId: string) {
  const response = await apiClient.get<ApiResponse<SeatMap>>(
    `/events/${eventId}/seat-map`,
  );
  if (!response.data.result) throw new Error("Không nhận được sơ đồ ghế");
  return response.data.result;
}
