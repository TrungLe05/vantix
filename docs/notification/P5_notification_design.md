# Vantix — Phase 5: Notification & Check-in Design

> **Trạng thái:** Đã triển khai (14/11/2026), kiểm chứng bằng integration test (Postgres/Redis thật; SMTP, Kafka, user-service giả lập; WebSocket thật trên cổng ngẫu nhiên). **Chưa chạy end-to-end** với Kafka + SMTP thật và chưa có E2E trình duyệt — xem mục 8.
> **Liên quan:** ../00_roadmap_phases_v1.md (P5), ../payment/P4_payment_design.md, P5_notification_api_design.md, P5_notification_ui_plan.md, `DECISIONS.md` D31–D36.
> **Phạm vi:** phát hành vé + QR sau thanh toán, check-in chống quét trùng (Order); thông báo trong ứng dụng + email + WebSocket (notification-service); email tài khoản P1 (OTP, mật khẩu tạm STAFF) lần đầu thực sự được gửi.

## 1. Nguyên tắc

- **Order sở hữu vé và check-in** (roadmap mục 2): chỉ Order biết ai mua vé nào. Payment vẫn chỉ biết tiền (D07).
- **Notification chỉ nghe sự kiện nghiệp vụ của Order** (D33), không nghe `payment.*`: tiền vào chưa chắc là "đặt vé thành công" (thanh toán trễ → hoàn tiền, D28).
- Thông báo trong ứng dụng không phụ thuộc email: SMTP hỏng/chưa cấu hình thì người dùng vẫn thấy thông báo.
- Bí mật (OTP, mật khẩu tạm, mã vé) không vào log, không vào payload Kafka gửi Notification, không lưu ở bảng `notifications`.

## 2. Quyết định đã chốt ở P5

| # | Quyết định | Lý do ngắn |
|---|---|---|
| D31 | Vé phát hành **cùng transaction** ghi nhận thanh toán: Order đi `PENDING_PAYMENT → PAID → TICKETS_ISSUED` trong một lần; mỗi người vào cổng một vé, mã ngẫu nhiên 192 bit | Không tồn tại đơn "đã trả tiền mà chưa có vé"; mã không đoán/suy ra được |
| D32 | Check-in bằng **update có điều kiện** `VALID → CHECKED_IN`, phạm vi theo event; quét trùng trả 4017 kèm ai quét/lúc nào; vé sự kiện khác báo như không tồn tại | Hai cổng quét cùng lúc chỉ một bên qua; STAFF sự kiện A không dò được vé sự kiện B |
| D33 | Notification nghe `order.tickets-issued`, `order.refund-requested`, `order.refunded` (topic mới, Order phát khi nhận `refund.completed`) | Chỉ Order biết buyer và lý do hoàn; `refund.completed` của Payment không có buyerId |
| D34 | Email người nhận lấy từ user-service qua `GET /api/internal/users/{id}/contact` (role `INTERNAL`) | Không nhân bản PII của mọi user sang Notification; theo quy ước `/internal/**` của D30 |
| D35 | WebSocket thuần (không STOMP) tại `/api/notifications/ws`, xác thực bằng **ticket một lần 30 giây** lấy qua REST có JWT; giới hạn Origin | Trình duyệt không gửi được header khi mở WebSocket; JWT trên URL dễ lọt log; không thêm thư viện frontend |
| D36 | Ảnh QR sinh ở backend (`com.google.zxing:core`), STAFF quét bằng `BarcodeDetector` của trình duyệt hoặc nhập mã | Không thêm dependency frontend (chủ repo chọn 14/11/2026) |

## 3. Mô hình dữ liệu

### 3.1 `orders_service` — migration `V4__tickets.sql`

~~~mermaid
erDiagram
    ORDERS ||--o{ TICKETS : issues
    ORDER_ITEMS ||--o{ TICKETS : "1 (ghế) hoặc N (GA)"
    TICKETS {
        uuid id PK
        uuid order_id FK
        uuid order_item_id FK
        uuid event_id
        uuid buyer_id
        uuid ticket_type_id
        uuid seat_id "null với GA"
        string ticket_name_snapshot
        string seat_label_snapshot
        string code UK "base64url 32 ký tự"
        string status "VALID | CHECKED_IN | VOID"
        timestamp checked_in_at
        uuid checked_in_by "ADMIN/STAFF userId"
    }
~~~

CHECK: `CHECKED_IN` bắt buộc có `checked_in_at` và `checked_in_by`. Vé `VOID` giữ dấu vết check-in nếu có.

### 3.2 `notifications_service` — migration `V1__create_notifications.sql`

~~~mermaid
erDiagram
    NOTIFICATIONS {
        uuid id PK
        uuid user_id
        string type "TICKETS_ISSUED | REFUND_REQUESTED | REFUND_COMPLETED"
        string title
        text body
        string link "/orders/{id}"
        uuid order_id
        string dedup_key UK "một bản tin nguồn → một thông báo"
        timestamp read_at
        string email_status "PENDING | SENT | FAILED | SKIPPED"
        int email_attempts
        timestamp next_email_attempt_at "null = không gửi nữa"
        string email_last_error "chỉ loại lỗi"
    }
~~~

`dedup_key`: `tickets-issued:{orderId}`, `refund-requested:{refundId}`, `refunded:{refundId}`.

## 4. Vòng đời vé

~~~mermaid
stateDiagram-v2
    [*] --> VALID: payment.succeeded (Order PENDING_PAYMENT)
    VALID --> CHECKED_IN: quét ở cổng (ADMIN/STAFF)
    VALID --> VOID: buyer hủy đơn / ADMIN hủy event
    CHECKED_IN --> CHECKED_IN: quét lại → 4017
~~~

- Ghế = 1 vé; GA số lượng N = N vé (mỗi người một mã).
- Buyer **không** hoàn được đơn đã có vé check-in (hoàn cả đơn, D29) — OR10 trả `refundable=false`, OR11 trả 4015.
- ADMIN hủy event vẫn hoàn 100% (D16) kể cả đơn đã có vé check-in; vé chưa dùng thành `VOID`, vé đã check-in giữ nguyên làm lịch sử.
- Vé `VOID` không có QR (OR12 trả 4018) và không check-in được (4018).

## 5. Luồng check-in

~~~mermaid
sequenceDiagram
    participant S as STAFF (frontend /check-in)
    participant G as Gateway
    participant O as order-service
    S->>G: POST /api/orders/tickets/check-in {eventId, code}
    G->>O: + X-User-Id, X-User-Role (STAFF)
    O->>O: UPDATE tickets SET CHECKED_IN WHERE code AND event_id AND status = VALID
    alt 1 dòng
        O-->>S: 200 {ticketName, seatLabel, checkedInAt, checkedInBy}
    else 0 dòng
        O->>O: đọc lại vé theo code
        O-->>S: 4016 (không có / sai event) · 4017 + lần quét trước · 4018 (VOID)
    end
~~~

Kiểm chứng: 10 lần quét đồng thời cùng mã → đúng 1 thành công, 9 lần 4017.

## 6. Luồng thông báo

~~~mermaid
sequenceDiagram
    participant O as order-service
    participant K as Kafka
    participant N as notification-service
    participant U as user-service
    participant M as SMTP
    participant B as Buyer (WebSocket)
    O->>O: 1 transaction: vé + TICKETS_ISSUED + outbox order.tickets-issued
    O->>K: order.tickets-issued (key = orderId)
    K->>N: consume
    N->>N: INSERT notifications ... ON CONFLICT (dedup_key) DO NOTHING
    N-->>B: sau commit: {"type":"notification", ...} qua WebSocket
    loop EmailDispatchScheduler (2 giây)
        N->>N: giành dòng PENDING/FAILED đến hạn
        N->>U: GET /internal/users/{id}/contact (INTERNAL)
        N->>M: gửi email
        N->>N: SENT / FAILED (backoff 30s × 2^n, tối đa 5 lần) / SKIPPED
    end
~~~

| Nguồn | Thông báo | Ghi chú |
|---|---|---|
| `order.tickets-issued` | "Your tickets are confirmed" | Không chứa mã vé |
| `order.refund-requested` | "Refund in progress" — nội dung theo lý do (buyer hủy / event hủy / thanh toán trễ) | Notification là consumer group thứ hai của topic P4 |
| `order.refunded` | "Refund completed" | Order phát khi nhận `refund.completed` |
| `user.email-verification-requested`, `user.forgot-password-requested` | Email OTP, **không lưu DB** | Group riêng `notification-service-account`, `auto.offset.reset=latest`; bỏ bản tin cũ hơn 10 phút (OTP đã hết hạn) |
| `user.staff-account-created` | Email mật khẩu tạm, **không lưu DB** | Không áp hạn (mật khẩu tạm vẫn dùng được) |

- Email không có SMTP (`MAIL_HOST` trống): thông báo `SKIPPED`, email tài khoản log `EMAIL_SKIPPED type=… userId=…` (không có mã). Dev muốn nhận OTP phải cấu hình SMTP.
- User không còn tồn tại (404 từ user-service) → `SKIPPED`; user-service lỗi → `FAILED`, thử lại.
- Email tài khoản lỗi SMTP → ném lỗi để Kafka retry bản tin (Kafka phát lại có thể gửi trùng email — chấp nhận).

## 7. WebSocket

- Client: `POST /api/notifications/ws-ticket` (JWT) → `{ticket, expiresAt}` → `new WebSocket(wss://…/api/notifications/ws?ticket=…)`.
- Server: `WsTicketHandshakeInterceptor` lấy và xóa ticket (dùng một lần, 30 giây); sai → 401, không mở kết nối. `setAllowedOrigins(FRONTEND_ORIGIN)` chặn trang lạ.
- Gateway: `GET /api/notifications/ws` trong `public-endpoints` (handshake không có JWT); route `/api/notifications/**` sẵn có, Gateway tự đổi sang `ws://` khi có header Upgrade.
- Đẩy là best-effort; client nối lại với backoff tăng dần (tối đa 30 giây) và badge vẫn refetch mỗi phút.
- Phiên và ticket nằm trong bộ nhớ: đúng cho một instance. Chạy nhiều instance cần Redis cho ticket và pub/sub giữa instance (P9).

## 8. Giới hạn và việc còn lại

- Chưa chạy end-to-end với Kafka + SMTP thật; chưa có Playwright cho vé/check-in/thông báo; chưa kiểm tra camera trên thiết bị thật.
- Nội dung thông báo không có tên sự kiện (Notification không gọi Product); email văn bản thuần, tiếng Anh.
- Thông báo cảnh báo bot (roadmap P5 mục 1) chờ P6 có topic `bot_score.evaluated`.
- Chưa có thông báo cho STAFF/ADMIN; chuông thông báo chỉ hiện cho BUYER.
- "Ai quét" trả `checkedInBy` là userId (Order không có tên STAFF); UI hiện "you" hoặc 8 ký tự đầu của id.
- Chuyển nhượng vé (FR4 mốc < 24 giờ) chưa làm.
- `PageResponse` nay có 3 bản sao (product, order, notification) — đủ rule of 3, nên tách vào common-lib ở thay đổi riêng.
