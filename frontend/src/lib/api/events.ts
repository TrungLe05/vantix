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
    throw new Error("Could not retrieve the event list");
  return response.data.result;
}

export async function getAllEvents() {
  const firstPage = await getEvents(0, 100);
  if (firstPage.totalPages <= 1) return firstPage.content;

  const remainingPages = await Promise.all(
    Array.from({ length: firstPage.totalPages - 1 }, (_, index) =>
      getEvents(index + 1, 100),
    ),
  );
  return [firstPage, ...remainingPages].flatMap((page) => page.content);
}

export async function getEvent(eventId: string) {
  const response = await apiClient.get<ApiResponse<EventDetail>>(
    `/events/${eventId}`,
  );
  if (!response.data.result)
    throw new Error("Could not retrieve event details");
  return response.data.result;
}

export async function getSeatMap(eventId: string) {
  const response = await apiClient.get<ApiResponse<SeatMap>>(
    `/events/${eventId}/seat-map`,
  );
  if (!response.data.result) throw new Error("Could not retrieve the seat map");
  return response.data.result;
}
