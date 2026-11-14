import { apiClient } from "./client";
import type { ApiResponse, PaginatedResponse } from "@/types/common";
import { isAxiosError } from "axios";
import type {
  CheckInResult,
  CheckoutSession,
  Order,
  RefundQuote,
} from "@/types/orders";

// OR02 trả trang; không truyền status = Order đang hoạt động (CART, PENDING_PAYMENT).
// Mỗi buyer có tối đa một CART mỗi event nên 100 phần tử đủ cho giỏ hàng/trang orders hiện tại.
export async function getOrders() {
  const response = await apiClient.get<ApiResponse<PaginatedResponse<Order>>>("/orders", {
    params: { size: 100 },
  });
  return response.data.result?.content ?? [];
}

// Đơn đã thanh toán (kể cả đang/đã hoàn) cho mục "Purchases" của trang orders
export async function getPurchasedOrders() {
  const response = await apiClient.get<ApiResponse<PaginatedResponse<Order>>>("/orders", {
    params: { status: "PAID,TICKETS_ISSUED,REFUND_PENDING,REFUNDED", size: 100 },
  });
  return response.data.result?.content ?? [];
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

// OR08 — hủy giỏ, nhả mọi hold ngay. Backend chỉ nhận Order CART; PENDING_PAYMENT trả 4005 (D27)
export async function cancelOrder(orderId: string) {
  const response = await apiClient.delete<ApiResponse<Order>>(`/orders/${orderId}`);
  if (!response.data.result) throw new Error("Could not cancel this cart");
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

// OR09 — lấy link thanh toán Stripe cho Order PENDING_PAYMENT. Idempotent theo Order ở backend
// (gọi lại nhận đúng link cũ) nên không cần Idempotency-Key.
export async function startCheckout(orderId: string) {
  const response = await apiClient.post<ApiResponse<CheckoutSession>>(
    `/orders/${orderId}/checkout`,
  );
  if (!response.data.result?.checkoutUrl)
    throw new Error("Could not start payment for this order");
  return response.data.result;
}

// OR10 — xem trước số tiền hoàn theo chính sách (100% / 50% / 0%)
export async function getRefundQuote(orderId: string) {
  const response = await apiClient.get<ApiResponse<RefundQuote>>(
    `/orders/${orderId}/refund-quote`,
  );
  if (!response.data.result) throw new Error("Could not load the refund amount");
  return response.data.result;
}

// OR11 — hủy đơn đã thanh toán và yêu cầu hoàn; server tự tính lại % tại thời điểm gọi
export async function requestRefund(orderId: string) {
  const response = await apiClient.post<ApiResponse<Order>>(
    `/orders/${orderId}/refund`,
  );
  if (!response.data.result) throw new Error("Could not request a refund");
  return response.data.result;
}

// OR12 — ảnh QR của vé (PNG, cần JWT nên tải bằng apiClient rồi tạo object URL)
export async function getTicketQr(orderId: string, ticketId: string) {
  const response = await apiClient.get<Blob>(
    `/orders/${orderId}/tickets/${ticketId}/qr`,
    { responseType: "blob" },
  );
  return response.data;
}

export type CheckInOutcome =
  | { outcome: "CHECKED_IN"; ticket: CheckInResult }
  | { outcome: "ALREADY_CHECKED_IN"; ticket: CheckInResult };

// OR13 — quét vé (ADMIN/STAFF). Quét trùng (4017) không phải lỗi hệ thống: trả về lần quét trước để
// STAFF xử lý tại chỗ; các lỗi khác (4016 không tìm thấy, 4018 vé đã hủy) ném ra như bình thường.
export async function checkInTicket(
  eventId: string,
  code: string,
): Promise<CheckInOutcome> {
  try {
    const response = await apiClient.post<ApiResponse<CheckInResult>>(
      "/orders/tickets/check-in",
      { eventId, code },
    );
    if (!response.data.result) throw new Error("Could not check in this ticket");
    return { outcome: "CHECKED_IN", ticket: response.data.result };
  } catch (error) {
    if (isAxiosError<ApiResponse<CheckInResult>>(error)) {
      const data = error.response?.data;
      if (data?.code === 4017 && data.result) {
        return { outcome: "ALREADY_CHECKED_IN", ticket: data.result };
      }
    }
    throw error;
  }
}
