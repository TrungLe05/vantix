# Vantix — Phase 5: Notification & Check-in API Design

> **Trạng thái:** Hợp đồng đã triển khai, đối chiếu với code ngày 14/11/2026.
> **Liên quan:** P5_notification_design.md, ../payment/P4_payment_api_design.md, ../order/P3_order_api_design.md.
> **Path:** Gateway expose `/api/orders/...`, `/api/notifications/...`; service có context path `/api`.

## 1. Mã lỗi mới

| Code | Error | HTTP | Trường hợp |
|---|---|---:|---|
| 4016 | TICKET_NOT_FOUND | 404 | Mã vé không tồn tại, thuộc sự kiện khác, hoặc ticketId không thuộc Order |
| 4017 | TICKET_ALREADY_CHECKED_IN | 409 | Vé đã check-in; `result` = thông tin lần quét đầu (shape `CheckInResponse`) |
| 4018 | TICKET_NOT_VALID | 409 | Vé `VOID` (đơn đã hoàn / event đã hủy) |
| 6001 | NOTIFICATION_NOT_FOUND | 404 | Thông báo không tồn tại hoặc của người khác |

`4015 REFUND_NOT_ALLOWED` nay cũng trả khi đơn có vé đã check-in. `2601 USER_NOT_FOUND` dùng cho API nội bộ.

## 2. Order API

### Thay đổi OrderResponse

- Thêm `tickets: TicketResponse[]` (rỗng với `CART`/`PENDING_PAYMENT`):
  ~~~json
  { "id": "uuid", "ticketTypeId": "uuid", "ticketName": "VIP", "seatLabel": "Floor · A-01",
    "code": "Zk3…32 ký tự", "status": "VALID", "checkedInAt": null }
  ~~~
  `status`: `VALID` | `CHECKED_IN` | `VOID`. `code` chỉ có trong response của chính buyer (OR03).
- `payment.succeeded` cho Order `PENDING_PAYMENT` nay chuyển thẳng sang **`TICKETS_ISSUED`** (P4 ghi `PAID`; `PAID` chỉ còn là trạng thái trung gian trong cùng transaction). Mọi chỗ kiểm tra "đã thanh toán" dùng `PAID` hoặc `TICKETS_ISSUED`.
- OR10 trả `refundable=false`, `percent=0` khi đơn có vé `CHECKED_IN`.

### OR12 — Ảnh QR của vé
`GET /api/orders/{orderId}/tickets/{ticketId}/qr` — BUYER, chủ đơn.
Trả `image/png` (320×320, QR mã hóa `code`), header `Cache-Control: no-store`. Lỗi trả JSON như thường: `4001` (Order không của buyer), `4016`, `4018`.

### OR13 — Quét vé
`POST /api/orders/tickets/check-in` — `@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")`.

~~~json
{ "eventId": "uuid", "code": "Zk3…" }
~~~
`eventId` bắt buộc, `code` 1–64 ký tự (được trim). Sai → `1001`.

~~~json
{ "code": 200, "result": { "ticketId": "uuid", "orderId": "uuid", "ticketName": "VIP", "seatLabel": "Floor · A-01",
  "checkedInAt": "2026-11-20T18:02:11Z", "checkedInBy": "staff-uuid" } }
~~~
Quét trùng:
~~~json
{ "code": 4017, "message": "Vé đã được check-in trước đó", "result": { "ticketId": "uuid", "checkedInAt": "…", "checkedInBy": "uuid", "…": "…" } }
~~~
Lỗi khác: `4016`, `4018`, `1003` (thiếu danh tính), `1004` (BUYER).

## 3. Notification API (mọi vai trò đã đăng nhập, chỉ dữ liệu của chính user)

| # | Method | Path | Kết quả |
|---|---|---|---|
| NT01 | GET | `/api/notifications?page=0&size=20` | `PageResponse<NotificationResponse>`, mới nhất trước; `size` 1–100 |
| NT02 | GET | `/api/notifications/unread-count` | `{count}` |
| NT03 | PATCH | `/api/notifications/{id}/read` | `NotificationResponse`; idempotent (giữ mốc đọc đầu); của người khác → `6001` |
| NT04 | POST | `/api/notifications/read-all` | `{updated}` |
| NT05 | POST | `/api/notifications/ws-ticket` | `{ticket, expiresAt}` — dùng một lần, 30 giây |
| NT06 | GET (Upgrade) | `/api/notifications/ws?ticket=…` | WebSocket; công khai ở Gateway, xác thực bằng ticket; sai/dùng lại → 401 |

~~~json
{ "id": "uuid", "type": "TICKETS_ISSUED", "title": "Your tickets are confirmed",
  "body": "We received your payment of $150.00. 3 tickets are ready - open your order to show the QR code at the entrance.",
  "link": "/orders/uuid", "orderId": "uuid", "read": false, "createdAt": "…" }
~~~

Bản tin WebSocket (server → client, một chiều): `{"type":"notification","notification":{…NotificationResponse}}`. Client gửi gì cũng bị bỏ qua.

## 4. API nội bộ user-service

`GET /api/internal/users/{userId}/contact` — **không route qua Gateway**, `@PreAuthorize("hasRole('INTERNAL')")`. Notification gửi `X-User-Id: 00000000-0000-0000-0000-000000000000`, `X-User-Role: INTERNAL`. Gateway luôn xóa `X-User-*` của client và không phát role `INTERNAL`.

~~~json
{ "code": 200, "result": { "userId": "uuid", "email": "buyer@example.com", "fullName": "Alex Buyer" } }
~~~
User không có / đã xóa mềm → `2601`. User-service nay bật `@EnableMethodSecurity` (trước đó `@PreAuthorize` của `POST /users/staff` không có hiệu lực — vẫn an toàn nhờ rule URL).

## 5. Kafka

| Topic | Producer → Consumer | Payload (key = orderId) |
|---|---|---|
| `order.tickets-issued` (mới) | order → notification | `orderId`, `buyerId`, `eventId`, `ticketCount`, `totalAmount`, `currency`, `issuedAt` — **không có mã vé** |
| `order.refunded` (mới) | order → notification | `refundId`, `orderId`, `buyerId`, `eventId`, `amount`, `currency`, `reason`, `completedAt` |
| `order.refund-requested` (P4) | order → payment, **notification** | không đổi |
| `user.*` (P1) | user → notification | không đổi; nay lần đầu có consumer |

Cả hai topic mới đi qua `order_event_outbox` (cột `topic` + `payload`). Consumer group: `notification-service` (`app.notification.kafka-consumer-group`) cho event Order; `notification-service-account` (`app.notification.account-consumer-group`, `auto.offset.reset=latest`) cho `user.*`.

## 6. Cấu hình notification-service

| Thuộc tính | Biến môi trường | Mặc định |
|---|---|---|
| `spring.mail.host` / `port` / `username` / `password` | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | trống → không gửi email |
| `spring.mail.properties.mail.smtp.auth` / `starttls.enable` | `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS` | `true` / `true` |
| `app.notification.mail-from` | `MAIL_FROM` | `no-reply@vantix.local` |
| `app.notification.frontend-base-url` | `FRONTEND_ORIGIN` | `http://localhost:3000` (link trong email + Origin WebSocket) |
| `app.user-service.base-url` | `USER_SERVICE_URL` | `http://localhost:9004/api` |
| `app.notification.email-max-attempts` / `email-base-backoff` | — | 5 / `PT30S` |
| `app.notification.ws-ticket-ttl` | — | `PT30S` |

## 7. Kiểm chứng

- `TicketCheckInIntegrationTest` (9 case): phát hành 1 vé/ghế + N vé/GA, mã khác nhau, event không chứa mã; thanh toán lặp không phát hành lần hai; QR giải mã ra đúng mã và chỉ chủ đơn xem được; quét lần hai trả 4017 + người quét đầu; 10 lần quét song song → 1 thành công; sai event = 4016; phân quyền; hoàn tiền làm vé VOID + phát `order.refunded` đúng một lần; đơn có vé check-in không hoàn được nhưng hủy event vẫn hoàn.
- `NotificationFlowIntegrationTest` (12 case) và `NotificationWebSocketIntegrationTest` (2 case, WebSocket thật).
- `InternalUserContactIntegrationTest` (3 case).
