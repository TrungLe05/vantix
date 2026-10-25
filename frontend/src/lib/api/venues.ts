import { apiClient } from "./client";
import type { ApiResponse } from "@/types/common";

export interface Venue {
  id: string;
  name: string;
  address: string;
  city: string;
}

export interface CreateVenueRequest {
  name: string;
  address: string;
  city: string;
}

export async function getVenues() {
  const response = await apiClient.get<ApiResponse<Venue[]>>("/venues");
  if (!response.data.result) {
    throw new Error("Could not retrieve the venue list");
  }
  return response.data.result;
}

export async function createVenue(request: CreateVenueRequest) {
  const response = await apiClient.post<ApiResponse<Venue>>("/venues", request);
  if (!response.data.result) {
    throw new Error("Could not retrieve the created venue");
  }
  return response.data.result;
}
