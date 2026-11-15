import { apiClient } from "./client";
import type { ApiResponse, PaginatedResponse } from "@/types/common";
import type {
  AppNotification,
  NotificationSocketMessage,
} from "@/types/notifications";

// NT01 — thông báo của chính user, mới nhất trước
export async function getNotifications(page = 0, size = 20) {
  const response = await apiClient.get<
    ApiResponse<PaginatedResponse<AppNotification>>
  >("/notifications", { params: { page, size } });
  return response.data.result?.content ?? [];
}

// NT02
export async function getUnreadNotificationCount() {
  const response = await apiClient.get<ApiResponse<{ count: number }>>(
    "/notifications/unread-count",
  );
  return response.data.result?.count ?? 0;
}

// NT03
export async function markNotificationRead(notificationId: string) {
  const response = await apiClient.patch<ApiResponse<AppNotification>>(
    `/notifications/${notificationId}/read`,
  );
  if (!response.data.result) throw new Error("Could not update this notification");
  return response.data.result;
}

// NT04
export async function markAllNotificationsRead() {
  await apiClient.post("/notifications/read-all");
}

/**
 * NT05 + NT06 — mở WebSocket nhận thông báo realtime. Trình duyệt không gửi được header Authorization
 * khi mở WebSocket nên lấy ticket một lần (sống 30 giây) qua REST có JWT, rồi đặt ticket vào URL (D35).
 * Access token không bao giờ nằm trên URL.
 */
export async function openNotificationSocket(
  onMessage: (message: NotificationSocketMessage) => void,
) {
  const response = await apiClient.post<ApiResponse<{ ticket: string }>>(
    "/notifications/ws-ticket",
  );
  const ticket = response.data.result?.ticket;
  if (!ticket) throw new Error("Could not open the notification channel");
  const base = new URL(
    process.env.NEXT_PUBLIC_API_BASE_URL ?? "",
    window.location.origin,
  );
  base.protocol = base.protocol === "https:" ? "wss:" : "ws:";
  base.pathname = `${base.pathname.replace(/\/$/, "")}/notifications/ws`;
  base.search = new URLSearchParams({ ticket }).toString();
  const socket = new WebSocket(base.toString());
  socket.addEventListener("message", (event) => {
    try {
      const data = JSON.parse(String(event.data)) as NotificationSocketMessage;
      if (data.type === "notification") onMessage(data);
    } catch {
      // Bản tin không đúng định dạng: bỏ qua, danh sách vẫn đồng bộ khi refetch
    }
  });
  return socket;
}
