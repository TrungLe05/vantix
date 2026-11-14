export type NotificationType =
  | "TICKETS_ISSUED"
  | "REFUND_REQUESTED"
  | "REFUND_COMPLETED";

export interface AppNotification {
  id: string;
  type: NotificationType;
  title: string;
  body: string;
  // Đường dẫn tương đối trên frontend, ví dụ /orders/{id}
  link: string | null;
  orderId: string | null;
  read: boolean;
  createdAt: string;
}

// Bản tin server đẩy qua WebSocket /api/notifications/ws
export interface NotificationSocketMessage {
  type: "notification";
  notification: AppNotification;
}
