// src/lib/api/bot-flags.ts — OR14/OR15 (ADMIN)
import { apiClient } from "@/lib/api/client";
import type { BotFlag, BotFlagStatus } from "@/types/bot-flags";
import type { ApiResponse, PaginatedResponse } from "@/types/common";

export async function getBotFlags(status: BotFlagStatus | "ALL", page: number, size = 20) {
  const response = await apiClient.get<ApiResponse<PaginatedResponse<BotFlag>>>("/orders/bot-flags", {
    params: { status: status === "ALL" ? undefined : status, page, size },
  });
  if (!response.data.result) throw new Error("Could not load bot flags");
  return response.data.result;
}

export async function clearBotFlag(flagId: string) {
  const response = await apiClient.post<ApiResponse<BotFlag>>(`/orders/bot-flags/${flagId}/clear`);
  if (!response.data.result) throw new Error("Could not clear this flag");
  return response.data.result;
}
