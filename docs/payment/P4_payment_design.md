# Vantix — Phase 4: Payment Design

> **Trạng thái:** Đã triển khai (14/11/2026) và kiểm chứng bằng integration test với Stripe giả lập. **Chưa chạy với Stripe thật** (cần `STRIPE_SECRET_KEY`/`STRIPE_WEBHOOK_SECRET` test mode) — xem mục 9.
> **Liên quan:** ../00_roadmap_phases_v1.md (P4), ../order/P3_order_design.md, P4_payment_api_design.md, P4_payment_ui_plan.md, `DECISIONS.md` D27–D30.
> **Phạm vi:** thanh toán Order `PENDING_PAYMENT` bằng Stripe Checkout (test, USD), webhook idempotent, kết quả về Order qua Kafka, hoàn tiền (3 mốc + hủy event + thanh toán trễ). Phát hành vé/QR và check-in thuộc P5.

## 1. Nguyên tắc

- **Payment chỉ biết tiền (D07).** `payments_service` không lưu vé, ghế, event. Order tính số tiền phải trả và số tiền hoàn; Payment chỉ thực thi qua Stripe và báo kết quả.
- **Hướng gọi (D06):** Order → Payment đồng bộ (REST) chỉ để lấy link phiên thanh toán; Stripe → Payment bằng webhook; Payment → Order và Order → Payment (yêu cầu hoàn) bằng Kafka qua transactional outbox.
- **Tiền vào trễ không được gây bán trùng.** Order là nơi quyết định: chỉ Order còn `PENDING_PAYMENT` mới thành `PAID`; mọi trường hợp khác hoàn 100%.
- Mọi consumer và webhook idempotent (Kafka at-least-once, Stripe gửi lại webhook).

## 2. Quyết định đã chốt ở P4

| # | Quyết định | Lý do ngắn |
|---|---|---|
| D27 | Stripe **Checkout Session** (trang do Stripe host), không dùng PaymentIntent + Elements | Không thêm dependency frontend, không chạm dữ liệu thẻ. Hạn thanh toán = deadline hold của Order (không gia hạn); Payment tự expire phiên Stripe khi quá hạn. OR08 (hủy giỏ) chỉ còn cho `CART` |
| D28 | Thanh toán thành công đến khi Order đã `EXPIRED`/`CANCELLED` → **hoàn 100% tự động** (`LATE_PAYMENT`), không hồi sinh Order | Vé đã nhả có thể đã thuộc người khác; hồi sinh là bán trùng |
| D29 | Hoàn tiền **bất đồng bộ qua Kafka** (`order.refund-requested` → `refund.completed`/`refund.failed`), hoàn **cả đơn**, vé trả về kho ngay khi yêu cầu được ghi nhận | Một đường duy nhất cho 3 nguồn hoàn (buyer, hủy event, thanh toán trễ), trong đó 2 nguồn phát sinh trong consumer — không gọi REST đồng bộ trong transaction consumer |
| D30 | API nội bộ Order → Payment đặt dưới `/api/internal/**`, **không có route ở Gateway**; Order chuyển tiếp `X-User-Id`/`X-User-Role` | Giữ đúng Trust-The-Gateway mà không thêm cơ chế secret mới; cách ly mạng thật sự là việc của P9 |

Chi tiết và phương án đã cân nhắc: `DECISIONS.md`.

## 3. Mô hình dữ liệu

### 3.1 `payments_service`

~~~mermaid
erDiagram
    PAYMENTS ||--o{ REFUNDS : has
    PAYMENTS {
        uuid id PK
        uuid order_id UK "một Order một payment"
        uuid buyer_id
        decimal amount
        string currency "USD"
        string status "PENDING | SUCCEEDED | FAILED | EXPIRED"
        string stripe_session_id UK
        string stripe_payment_intent_id
        text checkout_url
        timestamp expires_at "deadline hold của Order"
        timestamp paid_at
    }
    REFUNDS {
        uuid id PK "refundId do Order sinh"
        uuid payment_id FK
        uuid order_id
        decimal amount
        string reason
        string status "PENDING | SUCCEEDED | FAILED"
        string stripe_refund_id UK
        string failure_reason "mã lỗi ngắn"
    }
    STRIPE_WEBHOOK_EVENTS {
        string event_id PK "Stripe event id"
        string event_type
        timestamp received_at
    }
    PAYMENT_EVENT_OUTBOX {
        uuid id PK
        string topic
        string message_key "orderId"
        text payload "JSON"
        timestamp published_at
        int attempt_count
        timestamp next_attempt_at
        timestamp dead_at
    }
~~~

### 3.2 Bổ sung ở `orders_service` (migration `V3__payment_and_refund.sql`)

- `orders.status` thêm `REFUND_PENDING`, `REFUNDED`; thêm cột `orders.paid_at`.
- Bảng `order_refunds` (`order_id` UNIQUE — mỗi Order tối đa một lần hoàn): `reason` (`BUYER_REQUEST` | `EVENT_CANCELLED` | `LATE_PAYMENT`), `refund_percent` (50 | 100), `amount`, `status` (`REQUESTED` | `COMPLETED` | `FAILED`), `failure_reason`, `completed_at`.
- `order_event_outbox` thêm `topic` (mặc định `cart.item_added`) và `payload` (JSON); `item_count`/`quantity` cho phép NULL. Bản ghi cũ giữ nguyên dạng cột riêng.

## 4. State machine

### Order (phần P4)

~~~mermaid
stateDiagram-v2
    PENDING_PAYMENT --> PAID: payment.succeeded (từ P5 đi tiếp ngay TICKETS_ISSUED, D31)
    PENDING_PAYMENT --> EXPIRED: deadline hold / payment.failed(EXPIRED)
    PENDING_PAYMENT --> CANCELLED: hủy event / payment.failed(FAILED)
    PAID --> REFUND_PENDING: buyer hủy (100%/50%) hoặc hủy event (100%)
    REFUND_PENDING --> REFUNDED: refund.completed
    EXPIRED --> EXPIRED: payment.succeeded trễ → refund LATE_PAYMENT
    CANCELLED --> CANCELLED: payment.succeeded trễ → refund LATE_PAYMENT
~~~

- `PAID`: hold `CONFIRMED → PURCHASED`, tồn GA `held → sold` (update nguyên tử `confirmSale`). Ghế đã bán được giữ bởi partial unique index `uq_holds_active_seat` (bao gồm `PURCHASED`) kể cả khi key Redis hết TTL.
- `REFUND_PENDING`: hold `PURCHASED → RELEASED`, item `CANCELLED`, tồn GA `sold` giảm (`releaseSold`) — vé trả về kho ngay.
- `refund.failed`: Order giữ `REFUND_PENDING`, refund `FAILED`, log `REFUND_FAILED` để xử lý tay.
- Thanh toán trễ: Order **không đổi trạng thái** (vẫn `EXPIRED`/`CANCELLED`), chỉ có thêm bản ghi refund.
- OR08: `PENDING_PAYMENT` không còn hủy được bởi buyer (4005); buyer bỏ thanh toán thì Order tự hết hạn theo deadline hold.

### Payment

`PENDING → SUCCEEDED` (webhook paid hoặc scheduler thấy phiên đã paid) · `PENDING → EXPIRED` (scheduler expire phiên / webhook `checkout.session.expired`) · `PENDING → FAILED` (`async_payment_failed`). Stripe báo paid cho payment đã đóng vẫn được ghi `SUCCEEDED` + phát `payment.succeeded` (log `PAYMENT_SUCCEEDED_AFTER_CLOSE`) để Order hoàn tiền.

## 5. Luồng thanh toán

~~~mermaid
sequenceDiagram
    participant B as Buyer (frontend)
    participant O as order-service
    participant P as payment-service
    participant S as Stripe
    participant K as Kafka
    B->>O: POST /orders/{id}/checkout (OR09)
    O->>O: Order của buyer, PENDING_PAYMENT, chưa quá hạn, total ≥ 0.50
    O->>P: POST /internal/payments/checkout-sessions (amount, expiresAt = hold deadline)
    P->>P: INSERT payments ... ON CONFLICT (order_id) DO NOTHING
    P->>S: Tạo Checkout Session (Idempotency-Key = checkout-{paymentId})
    S-->>P: session id + url
    P-->>O: checkoutUrl
    O-->>B: checkoutUrl
    B->>S: Trả tiền trên trang Stripe
    S->>P: Webhook checkout.session.completed (chữ ký)
    P->>P: 1 transaction: ghi event id, payment SUCCEEDED, outbox payment.succeeded
    P->>K: payment.succeeded (key = orderId)
    K->>O: consume
    O->>O: PENDING_PAYMENT → PAID, hold → PURCHASED, held → sold
    S-->>B: redirect /orders/{id}?payment=success
    B->>O: GET /orders/{id} (refetch tới khi PAID)
~~~

### Đồng bộ deadline và thanh toán trễ

- Stripe Checkout không cho đặt hạn dưới 30 phút, trong khi hold là 10 phút. `PaymentExpirationScheduler` (mỗi 5 giây) lấy payment `PENDING` quá `expires_at`, gọi Stripe **expire phiên**:
  - Stripe trả `expired` → payment `EXPIRED`, phát `payment.failed (EXPIRED)`.
  - Stripe báo phiên đã `complete/paid` (buyer trả sát giờ, hoặc webhook thất lạc) → ghi `SUCCEEDED`, phát `payment.succeeded`.
  - Stripe lỗi tạm thời → giữ `PENDING`, lần quét sau thử lại.
- Order vẫn có TTL độc lập (job expire 1 giây) làm lưới an toàn — không phụ thuộc tín hiệu của Payment.
- Khi `payment.succeeded` tới Order:

| Trạng thái Order | Xử lý |
|---|---|
| `PENDING_PAYMENT` (kể cả vừa quá deadline nhưng job chưa expire — hold vẫn còn) | → `PAID` |
| `PAID`, `TICKETS_ISSUED`, `REFUND_PENDING`, `REFUNDED` | Bỏ qua (bản tin lặp) |
| `EXPIRED`, `CANCELLED` | Giữ nguyên, tạo refund `LATE_PAYMENT` 100% |

## 6. Luồng hoàn tiền

~~~mermaid
sequenceDiagram
    participant B as Buyer
    participant O as order-service
    participant K as Kafka
    participant P as payment-service
    participant S as Stripe
    B->>O: GET /orders/{id}/refund-quote (OR10)
    O-->>B: percent, amount
    B->>O: POST /orders/{id}/refund (OR11)
    O->>O: 1 transaction: tính lại %, nhả vé, Order REFUND_PENDING, order_refunds REQUESTED, outbox
    O->>K: order.refund-requested (key = orderId)
    K->>P: consume
    P->>P: refunds PENDING (id = refundId), kiểm tra tổng hoàn ≤ đã thu
    P->>S: Refund (Idempotency-Key = refund-{refundId})
    S-->>P: succeeded
    P->>K: refund.completed
    K->>O: consume → refund COMPLETED, Order REFUNDED
~~~

### Chính sách (D16) — `RefundPolicy`

| Thời điểm yêu cầu so với giờ bắt đầu event (`startAt` từ Product) | % hoàn | Số tiền | Trạng thái Order |
|---|---:|---|---|
| Còn ≥ 7 ngày | 100% | `totalAmount` | `REFUND_PENDING → REFUNDED` |
| Từ 24 giờ tới dưới 7 ngày | 50% | `totalAmount × 50%`, làm tròn HALF_UP tới cent | `REFUND_PENDING → REFUNDED` |
| Dưới 24 giờ (hoặc đã diễn ra, hoặc không xác định được `startAt`) | 0% | — | Từ chối `4015`, Order giữ `PAID` |
| ADMIN hủy event | 100% | `totalAmount` | `REFUND_PENDING → REFUNDED` (tự động, không cần buyer yêu cầu) |
| Tiền vào khi Order đã `EXPIRED`/`CANCELLED` | 100% | số tiền Payment báo đã thu | Order giữ nguyên trạng thái |

- % được **tính lại ở server tại thời điểm OR11**, không tin số từ quote/client. Không gọi được Product → fail closed (`4010`).
- Hoàn cả đơn; chưa hỗ trợ hoàn từng vé (xem mục 9).
- Hủy event: `product.event-cancelled` → Order hủy đơn chưa thanh toán (như P3) **và** chuyển mọi đơn `PAID` sang `REFUND_PENDING` với refund `EVENT_CANCELLED` 100%. Chạy lại không tạo refund thứ hai (Order đã rời `PAID`, `order_refunds.order_id` UNIQUE).
- Payment từ chối (không có payment `SUCCEEDED`, hoặc tổng hoàn vượt số đã thu) → `refund.failed`. Stripe lỗi tạm thời → refund giữ `PENDING`, `RefundService.retryUnsentRefunds` thử lại mỗi 30 giây (an toàn nhờ idempotency key). Stripe trả `pending` → chờ webhook `refund.updated`/`refund.failed`.

## 7. Idempotency và concurrency

| Điểm | Cơ chế |
|---|---|
| Khởi tạo thanh toán lặp/song song | `payments.order_id` UNIQUE + `INSERT ... ON CONFLICT DO NOTHING`; Stripe Idempotency-Key `checkout-{paymentId}`; gọi lại trả link đã lưu |
| Webhook gửi lại | `stripe_webhook_events.event_id` PK ghi **cùng transaction** với thay đổi trạng thái; xử lý lỗi → rollback cả event id để Stripe gửi lại |
| `payment.succeeded` lặp / đua với job expire | Khóa dòng Order (`FOR UPDATE`) rồi rẽ nhánh theo trạng thái; chuyển `held → sold` bằng update có điều kiện |
| Yêu cầu hoàn lặp | Order: `order_refunds.order_id` UNIQUE + kiểm tra trạng thái sau khóa. Payment: `refunds.id` = refundId, Stripe Idempotency-Key `refund-{refundId}`; khóa payment khi cộng tổng đã hoàn |
| Mất event | Transactional outbox hai phía, backoff + dead-letter + gauge như D25 |

Thứ tự khóa giữ nguyên D23: Order → Hold. Handler thanh toán chỉ khóa Order (không lấy advisory lock theo event) nên không làm chậm thêm đường giữ vé.

## 8. Bảo mật

- Khóa Stripe chỉ lấy từ biến môi trường; không log message lỗi của Stripe, payload webhook, payload Kafka — chỉ log id nội bộ, loại/mã lỗi.
- Webhook là endpoint công khai duy nhất của Payment: xác thực `Stripe-Signature` (HMAC-SHA256, dung sai 5 phút); **chưa cấu hình `STRIPE_WEBHOOK_SECRET` thì từ chối mọi webhook** (fail closed).
- Không có dữ liệu thẻ đi qua hệ thống (Stripe host trang thanh toán).
- `/api/internal/**` không có route ở Gateway; ở dev cổng 9002 vẫn truy cập trực tiếp được như mọi service khác — siết bằng network isolation ở P9.

## 9. Giới hạn và việc còn lại

- **Chưa kiểm chứng với Stripe thật**: `StripeSdkGateway` (stripe-java 34.1.0) mới được biên dịch, chưa gọi API thật; cần chạy thẻ test `4242 4242 4242 4242` + `stripe listen` theo README.
- Buyer xác nhận giỏ sát deadline có rất ít thời gian trả tiền; trả không kịp thì được hoàn 100% tự động. Có nên cấp thêm "cửa sổ thanh toán" khi confirm hay không là quyết định sản phẩm còn mở.
- Chưa hỗ trợ đơn 0 đồng (vé miễn phí): OR09 trả `4014`.
- Chưa hoàn từng vé; chưa có API ADMIN xem/chạy lại refund `FAILED` (hiện xử lý tay bằng log `REFUND_FAILED` + DB).
- Giới hạn 10 vé vẫn theo Order, chưa theo buyer qua nhiều Order đã thanh toán (D24).
- Phát hành vé/QR (`TICKETS_ISSUED`), email xác nhận thanh toán/hoàn tiền: P5.
- Chưa có E2E Playwright cho luồng thanh toán (trang Stripe là bên thứ ba; cần Stripe test mode trong CI hoặc stub webhook).
