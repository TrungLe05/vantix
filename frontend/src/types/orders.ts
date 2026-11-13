export type OrderStatus =
  | "CART"
  | "PENDING_PAYMENT"
  | "PAID"
  | "TICKETS_ISSUED"
  | "CANCELLED"
  | "EXPIRED";

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

export interface Order {
  id: string;
  eventId: string;
  status: OrderStatus;
  currency: "USD";
  totalAmount: number;
  holdExpiresAt: string;
  items: OrderItem[];
}
