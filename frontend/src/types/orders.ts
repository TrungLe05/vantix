export type OrderStatus =
  | "CART"
  | "PENDING_PAYMENT"
  | "PAID"
  | "TICKETS_ISSUED"
  | "CANCELLED"
  | "EXPIRED"
  | "REFUND_PENDING"
  | "REFUNDED";

export interface OrderItem {
  id: string;
  ticketTypeId: string;
  ticketName: string;
  category: "SEATED" | "GENERAL_ADMISSION";
  status: "ACTIVE" | "REMOVED" | "EXPIRED" | "CANCELLED";
  seatId: string | null;
  seatLabel: string | null;
  quantity: number;
  unitPrice: number;
  subtotal: number;
}

// P4 — yêu cầu hoàn tiền của Order (tối đa một). LATE_PAYMENT: tiền vào sau khi giỏ đã hết hạn/bị hủy.
export interface OrderRefund {
  status: "REQUESTED" | "COMPLETED" | "FAILED";
  reason: "BUYER_REQUEST" | "EVENT_CANCELLED" | "LATE_PAYMENT";
  percent: number;
  amount: number;
  requestedAt: string;
  completedAt: string | null;
}

// P5 — vé đã phát hành; code chỉ trả cho chính buyer (nhập tay khi không quét được QR)
export interface OrderTicket {
  id: string;
  ticketTypeId: string;
  ticketName: string;
  seatLabel: string | null;
  code: string;
  status: "VALID" | "CHECKED_IN" | "VOID";
  checkedInAt: string | null;
}

export interface Order {
  id: string;
  eventId: string;
  status: OrderStatus;
  currency: "USD";
  totalAmount: number;
  holdExpiresAt: string;
  paidAt: string | null;
  refund: OrderRefund | null;
  tickets: OrderTicket[];
  items: OrderItem[];
}

// OR09 — link trang thanh toán do Stripe host
export interface CheckoutSession {
  orderId: string;
  checkoutUrl: string;
  expiresAt: string;
}

// OR10 — số tiền sẽ hoàn nếu yêu cầu ngay lúc này (server tính lại khi xác nhận)
export interface RefundQuote {
  orderId: string;
  refundable: boolean;
  percent: number;
  amount: number;
  currency: "USD";
  paidAmount: number;
  eventStartAt: string | null;
}

// OR13 — kết quả quét vé; cũng là result của lỗi 4017 (đã check-in: ai quét, lúc nào)
export interface CheckInResult {
  ticketId: string;
  orderId: string;
  ticketName: string;
  seatLabel: string | null;
  checkedInAt: string;
  checkedInBy: string;
}
