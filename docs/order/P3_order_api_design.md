# Vantix — Phase 3: Order API Design

> **Trạng thái:** Hợp đồng triển khai P3; đã đối chiếu với `backend/services/order-service` ngày 13/11/2026 (bổ sung OR08, shape item/availability, payload `cart.item_added`).
> **Liên quan:** P3_order_design.md, ../00_roadmap_phases_v1.md, ../auth/P1_auth_api_design.md, ../product/P2_product_api_design.md.
> **Path:** Gateway expose /api/orders/...; order-service có context path /api nên controller mapping chỉ dùng /orders.

## 1. Quy ước

- Buyer ID và role lấy từ X-User-Id/X-User-Role do Gateway xác minh; downstream dùng GatewayHeaderAuthFilter. Không nhận buyer ID từ client để phân quyền.
- Order gọi Product catalog/seat map bằng Spring Cloud OpenFeign client tại package `client`; base URL và timeout cấu hình trong `application.yaml`, wrapper đổi lỗi Feign sang `OrderErrorCode`.
- API giỏ yêu cầu BUYER; buyer chỉ truy cập Order của chính mình.
- ApiResponse<T> cho cả thành công/lỗi; ErrorCode của Order trong dải 4000–4999, xử lý qua GlobalExceptionHandler.
- Error code Order dùng dải 4000–4999; mã nghiệp vụ khai báo trong `OrderErrorCode`.
- Giá USD dạng decimal, tổng do server tính. Product xác nhận event/ticket/seat association, sales window và giá; Order lưu snapshot.
- OR01 (tạo hold), OR04 (thêm items) và OR06 (confirm) **bắt buộc** header `Idempotency-Key` (1–128 ký tự, thiếu/quá dài → 4011). Bản ghi lưu ở bảng `idempotency_keys`, unique theo `(buyer_id, operation, idempotency_key)` với operation `CREATE_CART`, `ADD_ITEMS:{orderId}`, `CONFIRM:{orderId}`; payload được hash SHA-256. Retry cùng buyer/key/payload trả kết quả gốc; cùng key với payload khác trả 4008. Idempotency không thay DB constraints.

| Code | Error | HTTP | Trường hợp |
|---|---|---:|---|
| 4001 | ORDER_NOT_FOUND | 404 | Không có hoặc không thuộc buyer |
| 4002 | EVENT_NOT_AVAILABLE | 409 | Event không publish, bị hủy hoặc ngoài sales window |
| 4003 | TICKET_NOT_AVAILABLE | 409 | Ticket/seat không khả dụng |
| 4004 | HOLD_EXPIRED | 409 | Deadline đã qua |
| 4005 | ORDER_STATE_CONFLICT | 409 | Thao tác sai state |
| 4006 | CART_ITEMS_UNAVAILABLE | 409 | Item không khả dụng; rollback toàn payload |
| 4007 | INVALID_ORDER_ITEMS | 400 | Item/quantity sai hoặc seat trùng |
| 4008 | IDEMPOTENCY_KEY_CONFLICT | 409 | Cùng key, payload khác |
| 4009 | EVENT_INVENTORY_NOT_READY | 503 | Inventory projection chưa sẵn sàng |
| 4010 | PRODUCT_SERVICE_UNAVAILABLE | 503 | Không thể xác minh event/ticket từ Product |
| 4011 | IDEMPOTENCY_KEY_REQUIRED | 400 | Thiếu key hoặc key dài hơn 128 ký tự |

## 2. Payload

Cart item:
~~~json
{
  "ticketTypeId": "uuid",
  "seatId": "uuid",
  "quantity": 1
}
~~~
SEATED cần ticketTypeId + seatId và quantity=1. GA cần ticketTypeId + quantity dương, bỏ seatId. Mọi item trong một request phải thuộc cùng event; duplicate seat không hợp lệ.

Order response shape:
~~~json
{
  "code": 200,
  "result": {
    "id": "uuid",
    "eventId": "uuid",
    "status": "CART",
    "currency": "USD",
    "totalAmount": 125.00,
    "holdExpiresAt": "2026-10-25T12:10:00Z",
    "items": [
      {
        "id": "uuid",
        "ticketTypeId": "uuid",
        "ticketName": "VIP",
        "category": "SEATED",
        "status": "ACTIVE",
        "seatId": "uuid",
        "seatLabel": "A-01",
        "quantity": 1,
        "unitPrice": 125.00,
        "subtotal": 125.00
      }
    ]
  }
}
~~~
Timestamp ISO-8601 UTC; null fields theo convention response. `status` của Order: `CART`, `PENDING_PAYMENT`, `PAID`, `TICKETS_ISSUED`, `CANCELLED`, `EXPIRED` (P3 chỉ phát sinh 4 trạng thái đầu/cuối, xem design). `items[].status`: `ACTIVE`, `REMOVED`, `EXPIRED`, `CANCELLED`. `ticketName`/`category`/`seatLabel`/`unitPrice` là snapshot lúc giữ, không đổi khi Product sửa giá. Order đang CART/PENDING_PAYMENT chỉ trả item `ACTIVE`; Order đã kết thúc (CANCELLED/EXPIRED…) trả toàn bộ item để xem lịch sử.

## 3. Endpoint

### OR01 — Tạo/reuse giỏ và giữ items
POST /api/orders · BUYER · Idempotency-Key

Body gồm eventId và items. Buyer bấm Continue; frontend chưa được coi là đã hold. Service kiểm tra catalog/sales window/toàn bộ items rồi tạo hoặc reuse CART cho event. GA cùng ticketType trong cart được gộp vào một OrderItem và một Hold; nếu item đã có thì tăng quantity của item đó. Nếu một item không khả dụng, rollback toàn bộ. Hold đầu tiên đặt deadline now + 10 minutes; items thêm sau không đổi deadline. Endpoint hiện trả 201 khi tạo mới hoặc reuse CART.

### OR02 — Danh sách Order
GET /api/orders · BUYER

Chỉ trả Order của buyer; hiện trả active orders CART/PENDING_PAYMENT, sắp xếp `createdAt` giảm dần, chưa phân trang hoặc lọc status. Order đã quá deadline được expire ngay khi đọc (bổ sung cho job định kỳ) nên có thể biến khỏi danh sách giữa hai lần gọi.

### OR03 — Chi tiết Order
GET /api/orders/{orderId} · BUYER

Trả item snapshot, total, status, deadline. Không tồn tại/không thuộc buyer dùng cùng ORDER_NOT_FOUND để tránh enumeration.

### OR04 — Thêm items
POST /api/orders/{orderId}/items · BUYER · Idempotency-Key

Body gồm items. Chỉ Order CART còn hạn; items thuộc event của Order; all-or-nothing; deadline giữ nguyên. GA cùng ticketType được cộng vào dòng active hiện có. Mỗi Order chỉ có tối đa một GA OrderItem active cho mỗi ticketType. Giá additions dùng unit price snapshot của dòng cart hiện có. Phát cart.item_added theo reliability design.

### OR05 — Bỏ item/nhả hold
DELETE /api/orders/{orderId}/items/{itemId} · BUYER

Chỉ Order CART của buyer. Nhả tồn đúng một lần; item xóa lặp không cộng trả lần hai. Xóa item cuối chuyển Order sang CANCELLED.

### OR05A — Cập nhật số lượng item GA
PATCH /api/orders/{orderId}/items/{itemId} · BUYER

Body `{ "quantity": 3 }` là số lượng đích, bắt buộc >= 1. Chỉ áp dụng cho GENERAL_ADMISSION; ghế SEATED giữ quantity=1 và dùng OR05 để bỏ item. Gửi lại cùng số lượng đích an toàn khi retry. Khi tăng, Order xác minh ticket/event và dùng atomic inventory update để giữ phần chênh lệch; thiếu tồn thì rollback toàn bộ. Khi giảm, Order nhả đúng phần chênh lệch. Cập nhật quantity, subtotal theo unit price snapshot, total và hold trong cùng transaction. Không thay đổi holdExpiresAt/hold.expiresAt; không cần idempotency key vì request đặt giá trị đích.

### OR06 — Xác nhận giỏ
POST /api/orders/{orderId}/confirm · BUYER · Idempotency-Key

Kiểm tra owner, CART, deadline chưa qua, event chưa hủy, sales window (gọi lại Product) và mọi hold còn hiệu lực. Chuyển CART sang PENDING_PAYMENT, hold sang CONFIRMED; không gia hạn deadline và không gọi Payment trong P3. Nếu quá hạn, Order được expire (commit) rồi trả 4004; Order không ở CART trả 4005.

### OR07 — Availability giao dịch
GET /api/orders/events/{eventId}/availability · public read-only

Trả trạng thái từng ghế và tồn theo ticket type, không trả chủ thể đang giữ:

~~~json
{
  "code": 200,
  "result": {
    "eventId": "uuid",
    "seats": [ { "seatId": "uuid", "status": "AVAILABLE" } ],
    "ticketTypes": [
      { "ticketTypeId": "uuid", "category": "GENERAL_ADMISSION", "totalQuantity": 500, "availableQuantity": 480 }
    ]
  }
}
~~~

- `seats[].status`: `AVAILABLE`, `HELD` (hold HELD/CONFIRMED còn hạn), `SOLD` (hold PURCHASED), `UNAVAILABLE` (event không PUBLISHED hoặc ghế catalog `INACTIVE`).
- GA: `availableQuantity = total - held - sold`, ép về 0 khi event không PUBLISHED hoặc ngoài sales window. SEATED: `totalQuantity` = số ghế `ACTIVE` của ticket type, `availableQuantity` = số ghế `AVAILABLE`.
- Side effect có chủ đích: endpoint expire các hold quá hạn của event, và nếu Product báo event `CANCELLED` thì hủy luôn các Order chưa thanh toán (lưới an toàn khi Kafka đến chậm).

Gateway allowlist endpoint này cho phép màn hình public đọc; UI ghép với catalog/seat map Product và refetch định kỳ 15 giây. Đây không phải reservation; chỉ OR01/OR04 tạo hold. Nếu Order API unavailable, UI không cho tiếp tục giữ.

### OR08 — Hủy giỏ
DELETE /api/orders/{orderId} · BUYER

Buyer tự hủy Order chưa thanh toán (CART hoặc PENDING_PAYMENT): nhả mọi hold đúng một lần (hold → RELEASED, item → CANCELLED) và chuyển Order sang CANCELLED. Gọi lại trên Order đã CANCELLED/EXPIRED trả nguyên trạng (idempotent tự nhiên, không cần key). Order ở trạng thái khác trả 4005. Frontend hiện chưa dùng endpoint này.

## 4. Thứ tự xử lý đề xuất

1. Xác thực Gateway identity/BUYER và ownership.
2. Đọc/kiểm tra Product catalog/sales window; fail closed nếu không xác minh event/ticket.
3. Validate một event, category, seat assignment và quantity.
4. Áp dụng idempotency key.
5. Trong transaction Order, bảo đảm một CART mỗi buyer/event và cập nhật hold bằng constraint/atomic update.
6. Conflict rollback toàn payload, không để lại item/hold một phần.
7. Ghi `cart.item_added` vào Order outbox cùng transaction; publisher gửi Kafka và retry nếu broker lỗi.

## 5. Kafka

### cart.item_added
Order phát khi buyer thêm items ở OR01/OR04. Topic `cart.item_added` (`KafkaTopics.ORDER_CART_ITEM_ADDED`), key = `orderId`. Payload đã triển khai (`CartItemAddedEvent`, record nội bộ order-service):

~~~json
{ "orderId": "uuid", "buyerId": "uuid", "eventId": "uuid", "itemCount": 2, "quantity": 3, "occurredAt": "2026-10-25T12:00:00Z" }
~~~

Ghi vào bảng `order_event_outbox` trong cùng transaction tạo hold; `OrderOutboxScheduler` poll mỗi 1 giây (`app.order.outbox-poll-interval-ms`), lỗi gửi thì retry sau 5 giây, **chưa có DLQ/alerting**. Không có tên/email/IP thô; `buyerId` là UUID nội bộ. Account/device features chỉ thêm khi pseudonymization, consent, retention khớp P8 (P6 sẽ chốt lại schema đầy đủ).

### product.event-cancelled
Consumer Order idempotent (`OrderEventListener`, group `order-service`). Event lặp không nhả tồn nhiều lần; event id được ghi vào bảng `cancelled_events` để chặn hold/confirm mới. Hủy/nhả Order chưa thanh toán; Order đã thanh toán theo refund P4. Payload lỗi được ném lại để Kafka retry.

### Redis
Khóa hold: `order:hold:seat:{seatId}` (SET NX, giá trị orderId) và `order:hold:ga:{holdId}`, TTL = thời gian còn lại của hold. Redis lỗi chỉ log; DB vẫn là lớp quyết định.

## 6. Ma trận lỗi

| Trường hợp | HTTP | Error |
|---|---:|---|
| Payload/quantity sai | 400 | 4007 hoặc Common validation |
| Chưa xác thực | 401 | Common/Gateway auth |
| Sai role | 403 | Common auth |
| Order không có/không thuộc buyer | 404 | 4001 |
| Event không được bán/đã hủy | 409 | 4002 |
| Ghế không ACTIVE/không thuộc ticket type đã chọn | 409 | 4003 |
| Item không có tồn | 409 | 4006 |
| Thiếu/sai Idempotency-Key | 400 | 4011 |
| Key dùng lại với payload khác | 409 | 4008 |
| Hold hết hạn | 409 | 4004 |
| Sai state | 409 | 4005 |
| Inventory projection chưa sẵn sàng | 503 | 4009 |
| Product unavailable (Feign lỗi/timeout 2s connect, 3s read) | 503 | 4010 |

## 7. Cần hoàn thiện khi triển khai

Giới hạn số vé tối đa mỗi request, pagination/filter cho danh sách Order, DLQ/alerting outbox, late payment/refund P4 và đối chiếu mã lỗi Common. Test tự động mới có `OrderRequestHasherTest`; còn thiếu test concurrency/integration (Testcontainers) và kết quả chạy k6 `k6/seat-contention.js`.
