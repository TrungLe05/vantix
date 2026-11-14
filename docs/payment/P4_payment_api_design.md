# Vantix — Phase 4: Payment API Design

> **Trạng thái:** Hợp đồng đã triển khai, đối chiếu với `backend/services/payment-service` và `order-service` ngày 14/11/2026.
> **Liên quan:** P4_payment_design.md, ../order/P3_order_api_design.md.
> **Path:** Gateway expose `/api/orders/...` và `/api/payments/...`; service có context path `/api` nên controller mapping không thêm `/api`.

## 1. Quy ước

- Buyer chỉ làm việc với **order-service** (OR09–OR11). Frontend không gọi payment-service.
- Payment có đúng một API công khai (webhook Stripe) và một API nội bộ cho Order.
- `ApiResponse<T>`; lỗi Order dải 4xxx, Payment dải 5xxx (50xx payment, 51xx Stripe, 52xx webhook).

### Mã lỗi mới

| Code | Error | HTTP | Trường hợp |
|---|---|---:|---|
| 4013 | PAYMENT_SERVICE_UNAVAILABLE | 503 | Order không gọi được Payment hoặc Payment/Stripe lỗi khi tạo phiên (fail closed) |
| 4014 | ORDER_NOT_PAYABLE | 409 | Tổng tiền < 0.50 USD (kể cả đơn 0 đồng) |
| 4015 | REFUND_NOT_ALLOWED | 409 | Order chưa thanh toán, hoặc mốc hoàn 0% (< 24 giờ trước event) |
| 5002 | PAYMENT_STATE_CONFLICT | 409 | Order đã có payment khác buyer/số tiền, hoặc payment đã `SUCCEEDED`/`FAILED` |
| 5003 | PAYMENT_AMOUNT_INVALID | 400 | Không phải USD, quá 2 chữ số thập phân, hoặc < 0.50 |
| 5004 | PAYMENT_DEADLINE_PASSED | 409 | `expiresAt` đã qua hoặc payment đã `EXPIRED` |
| 5101 | STRIPE_UNAVAILABLE | 503 | Stripe lỗi/timeout/chưa cấu hình khóa khi tạo phiên |
| 5201 | WEBHOOK_SIGNATURE_INVALID | 400 | Thiếu/sai `Stripe-Signature`, chữ ký quá 5 phút, hoặc chưa cấu hình signing secret |
| 5202 | WEBHOOK_PAYLOAD_INVALID | 400 | Body không phải JSON hoặc thiếu `id`/`type` |

Mã đã có được dùng lại: `4001` (Order không tồn tại/không thuộc buyer), `4004` (quá deadline hold), `4005` (sai trạng thái), `4010` (không gọi được Product khi tính % hoàn).

## 2. Order API (BUYER, qua Gateway)

### OR09 — Khởi tạo thanh toán
`POST /api/orders/{orderId}/checkout` — không body, không cần `Idempotency-Key`.

Điều kiện: Order của buyer, `PENDING_PAYMENT`, chưa quá `holdExpiresAt`, `totalAmount ≥ 0.50`. Order gọi PM01 với `expiresAt = holdExpiresAt`; **không ghi gì ở DB Order**. Idempotent tự nhiên theo Order: gọi lại nhận đúng `checkoutUrl` cũ (Payment unique theo `order_id`) — mạnh hơn Idempotency-Key vì phủ cả nhiều tab/thiết bị.

~~~json
{ "code": 200, "result": { "orderId": "uuid", "checkoutUrl": "https://checkout.stripe.com/c/pay/cs_test_...", "expiresAt": "2026-11-14T12:10:00Z" } }
~~~
Lỗi: `4001`, `4004` (Order đã/vừa hết hạn), `4005` (không ở `PENDING_PAYMENT`), `4014`, `4013`.

Frontend chuyển hướng tới `checkoutUrl`. Stripe đưa buyer về `{FRONTEND_ORIGIN}/orders/{orderId}?payment=success|cancelled`; tham số này **không phải bằng chứng đã thanh toán** — trạng thái thật là `status` của OR03.

### OR10 — Xem trước số tiền hoàn
`GET /api/orders/{orderId}/refund-quote`

~~~json
{ "code": 200, "result": { "orderId": "uuid", "refundable": true, "percent": 50, "amount": 85.00, "currency": "USD", "paidAmount": 170.00, "eventStartAt": "2026-11-20T12:00:00Z" } }
~~~
Order chưa `PAID` → `refundable=false`, `percent=0`, `amount=0.00`, `eventStartAt=null` (không gọi Product). Lỗi: `4001`, `4010`.

### OR11 — Hủy đơn đã thanh toán và yêu cầu hoàn
`POST /api/orders/{orderId}/refund` — không body.

Server tính lại % theo `RefundPolicy` tại thời điểm gọi. Trong một transaction: nhả vé (hold `PURCHASED → RELEASED`, item `CANCELLED`, tồn GA `sold` giảm), Order → `REFUND_PENDING`, tạo `order_refunds` `REQUESTED`, ghi outbox `order.refund-requested`. Trả `OrderResponse`. Gọi lại khi đã `REFUND_PENDING`/`REFUNDED` trả nguyên trạng (không lỗi, không tạo refund thứ hai). Lỗi: `4001`, `4015`, `4010`.

### Thay đổi API P3

- **OR08** `DELETE /api/orders/{id}`: chỉ còn nhận Order `CART`; `PENDING_PAYMENT` trả `4005` (trước đây hủy được). Lý do: D27.
- **OrderResponse** thêm `paidAt` (nullable) và `refund` (nullable):
  ~~~json
  "refund": { "status": "REQUESTED", "reason": "BUYER_REQUEST", "percent": 50, "amount": 85.00, "requestedAt": "...", "completedAt": null }
  ~~~
  `refund.status`: `REQUESTED` | `COMPLETED` | `FAILED`; `refund.reason`: `BUYER_REQUEST` | `EVENT_CANCELLED` | `LATE_PAYMENT`.
- **Order `status`** thêm `REFUND_PENDING`, `REFUNDED` (lọc được ở OR02: `?status=PAID,REFUND_PENDING,REFUNDED`). Mặc định OR02 vẫn là `CART,PENDING_PAYMENT`.
- Item của Order `REFUND_PENDING`/`REFUNDED` có `status = CANCELLED` (vé đã trả về kho).

## 3. Payment API

### PM01 — Tạo phiên thanh toán (nội bộ)
`POST /api/internal/payments/checkout-sessions` — **không route qua Gateway**. Header `X-User-Id`, `X-User-Role: BUYER` do order-service chuyển tiếp; `buyerId` trong body phải trùng `X-User-Id` (sai → `1004`, thiếu header → `1003`).

~~~json
{ "orderId": "uuid", "buyerId": "uuid", "amount": 170.00, "currency": "USD", "expiresAt": "2026-11-14T12:10:00Z" }
~~~
~~~json
{ "code": 200, "result": { "paymentId": "uuid", "orderId": "uuid", "status": "PENDING", "checkoutUrl": "https://...", "expiresAt": "..." } }
~~~
Thứ tự: validate (`5003`, `5004`) → `INSERT ... ON CONFLICT (order_id) DO NOTHING` → kiểm tra cùng buyer/số tiền và còn `PENDING` (`5002`/`5004`) → nếu chưa có phiên thì gọi Stripe **ngoài transaction** (`5101` khi lỗi; payment giữ `PENDING`, gọi lại sẽ thử tiếp) → lưu `stripe_session_id`, `checkout_url`.

### PM02 — Webhook Stripe (công khai)
`POST /api/payments/webhooks/stripe` — có trong `app.gateway.public-endpoints` và `permitAll`. Body là JSON thô của Stripe (đọc dạng chuỗi để chữ ký khớp), header `Stripe-Signature`.

| Event Stripe | Xử lý |
|---|---|
| `checkout.session.completed`, `checkout.session.async_payment_succeeded` với `payment_status = paid` | payment → `SUCCEEDED`, lưu `payment_intent`, outbox `payment.succeeded` |
| `checkout.session.completed` với `payment_status = unpaid` | Bỏ qua (chờ `async_payment_*`) |
| `checkout.session.expired` | payment `PENDING` → `EXPIRED`, outbox `payment.failed (EXPIRED)` |
| `checkout.session.async_payment_failed` | payment `PENDING` → `FAILED`, outbox `payment.failed (FAILED)` |
| `refund.updated`, `refund.failed` | Chốt refund còn `PENDING` theo `status` (`succeeded` / `failed` / `canceled`); tìm theo `stripe_refund_id`, dự phòng `metadata.refundId` |
| Khác, hoặc phiên không thuộc hệ thống | Trả 200, bỏ qua |

Trả `200 {code:200}` cho event đã xử lý hoặc lặp; `400` (`5201`/`5202`) khi chữ ký/body sai; lỗi xử lý trả 5xx để Stripe gửi lại.

Cấu hình endpoint trên Stripe cần ít nhất: `checkout.session.completed`, `checkout.session.expired`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`, `refund.updated`, `refund.failed`.

## 4. Kafka

Payload là `record` trong `commonlib-kafka/event`, hằng topic ở `KafkaTopics`. **Key = `orderId`** cho mọi topic dưới đây. Tất cả đi qua transactional outbox (`payment_event_outbox`, `order_event_outbox`).

| Topic | Producer → Consumer | Payload |
|---|---|---|
| `payment.succeeded` | payment → order | `paymentId`, `orderId`, `buyerId`, `amount`, `currency`, `paidAt` |
| `payment.failed` | payment → order | `paymentId`, `orderId`, `buyerId`, `reason` (`EXPIRED` \| `FAILED`), `failedAt` |
| `order.refund-requested` | order → payment | `refundId`, `orderId`, `buyerId`, `amount`, `currency`, `reason`, `requestedAt` |
| `refund.completed` | payment → order | `refundId`, `orderId`, `amount`, `currency`, `completedAt` |
| `refund.failed` | payment → order | `refundId`, `orderId`, `reason` (mã lỗi ngắn), `failedAt` |

~~~json
{ "paymentId": "uuid", "orderId": "uuid", "buyerId": "uuid", "amount": 170.00, "currency": "USD", "paidAt": "2026-11-14T12:03:21Z" }
~~~

Consumer group: `order-service` (`app.order.kafka-consumer-group`), `payment-service` (`app.payment.kafka-consumer-group`). Payload không chứa tên, email, dữ liệu thẻ. Notification (P5) có thể consume thêm các topic này.

## 5. Cấu hình

| Thuộc tính | Biến môi trường | Mặc định |
|---|---|---|
| `app.payment.stripe.secret-key` | `STRIPE_SECRET_KEY` | rỗng → tạo phiên trả `5101` |
| `app.payment.stripe.webhook-secret` | `STRIPE_WEBHOOK_SECRET` | rỗng → mọi webhook `5201` |
| `app.payment.frontend-base-url` | `FRONTEND_ORIGIN` | `http://localhost:3000` |
| `app.payment-service.base-url` (order) | `PAYMENT_SERVICE_URL` | `http://localhost:9002/api` |
| `app.payment.expiration-poll-interval-ms` | — | 5000 |
| `app.payment.refund-retry-interval-ms` / `refund-retry-min-age` | — | 30000 / `PT30S` |
| `app.payment.outbox-*` | — | như outbox Order (D25) |

Timeout Feign Order → Payment: connect 2s, read 12s (Payment gọi Stripe trong request; Stripe SDK connect 3s, read 8s, retry mạng 1 lần).

## 6. Kiểm chứng

- `PaymentFlowIntegrationTest` (19 case, Postgres thật, Stripe/Kafka giả lập): idempotent theo Order kể cả 8 request song song, từ chối số tiền/deadline/retry lệch, Stripe lỗi rồi retry, webhook chữ ký sai/thiếu/payload bị sửa, webhook lặp chỉ phát một event, expire theo deadline (expired / đã paid / Stripe lỗi), hoàn tiền (một lần, vượt số đã thu, payment chưa trả, lỗi tạm thời + retry, lỗi vĩnh viễn, chốt bằng webhook), outbox gửi object JSON, đăng ký `@KafkaListener`, phân quyền PM01.
- `OrderPaymentIntegrationTest` (15 case, Postgres/Redis thật): `PAID` + `held → sold`, bản tin lặp, ghế đã bán không giữ lại được sau khi Redis hết TTL, thanh toán trễ → refund 100% đúng một lần, `payment.failed`, OR09, OR08 từ chối `PENDING_PAYMENT`, 3 mốc hoàn, `refund.completed`/`refund.failed`, hủy event hoàn 100% đúng một lần.
- `RefundPolicyTest` (4 case): biên 7 ngày / 24 giờ, làm tròn.
- Chưa có: chạy với Stripe thật, E2E qua trình duyệt.
