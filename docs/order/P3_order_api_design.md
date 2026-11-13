# Vantix — Phase 3: Order API Design

> **Trạng thái:** Hợp đồng triển khai P3; các điểm đã chốt theo code được ghi rõ bên dưới.
> **Liên quan:** P3_order_design.md, ../00_roadmap_phases_v1.md, ../auth/P1_auth_api_design.md, ../product/P2_product_api_design.md.
> **Path:** Gateway expose /api/orders/...; order-service có context path /api nên controller mapping chỉ dùng /orders.

## 1. Quy ước

- Buyer ID và role lấy từ X-User-Id/X-User-Role do Gateway xác minh; downstream dùng GatewayHeaderAuthFilter. Không nhận buyer ID từ client để phân quyền.
- Order gọi Product catalog/seat map bằng Spring Cloud OpenFeign client tại package `client`; base URL và timeout cấu hình trong `application.yaml`, wrapper đổi lỗi Feign sang `OrderErrorCode`.
- API giỏ yêu cầu BUYER; buyer chỉ truy cập Order của chính mình.
- ApiResponse<T> cho cả thành công/lỗi; ErrorCode của Order trong dải 4000–4999, xử lý qua GlobalExceptionHandler.
- Error code Order dùng dải 4000–4999; mã nghiệp vụ khai báo trong `OrderErrorCode`.
- Giá USD dạng decimal, tổng do server tính. Product xác nhận event/ticket/seat association, sales window và giá; Order lưu snapshot.
- POST tạo hold và POST thêm items nhận Idempotency-Key. Retry cùng buyer/key/payload trả kết quả gốc; cùng key với payload khác trả conflict. Idempotency không thay DB constraints.

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
    "items": []
  }
}
~~~
Timestamp ISO-8601 UTC; null fields theo convention response.

## 3. Endpoint

### OR01 — Tạo/reuse giỏ và giữ items
POST /api/orders · BUYER · Idempotency-Key

Body gồm eventId và items. Buyer bấm Continue; frontend chưa được coi là đã hold. Service kiểm tra catalog/sales window/toàn bộ items rồi tạo hoặc reuse CART cho event. GA cùng ticketType trong cart được gộp vào một OrderItem và một Hold; nếu item đã có thì tăng quantity của item đó. Nếu một item không khả dụng, rollback toàn bộ. Hold đầu tiên đặt deadline now + 10 minutes; items thêm sau không đổi deadline. Endpoint hiện trả 201 khi tạo mới hoặc reuse CART.

### OR02 — Danh sách Order
GET /api/orders · BUYER

Chỉ trả Order của buyer; hiện trả active orders CART/PENDING_PAYMENT, chưa phân trang hoặc lọc status.

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

Kiểm tra owner, CART, deadline chưa qua, event chưa hủy và mọi hold còn hiệu lực. Chuyển CART sang PENDING_PAYMENT, hold sang CONFIRMED; không gia hạn deadline và không gọi Payment trong P3. Nếu quá hạn, expire và trả lỗi hold/state.

### OR07 — Availability giao dịch
GET /api/orders/events/{eventId}/availability · public read-only

Trả seat IDs đang giữ/đã bán và GA còn khả dụng, không trả chủ thể đang giữ. Gateway allowlist endpoint này cho phép màn hình public đọc; UI ghép với catalog/seat map Product và refetch định kỳ 15 giây. Đây không phải reservation; chỉ OR01/OR04 tạo hold. Nếu Order API unavailable, UI không cho tiếp tục giữ.

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
Order phát khi buyer thêm items ở OR01/OR04. Payload tối thiểu dự kiến gồm opaque eventId/orderId, occurredAt, action và số lượng cần phân tích. Không có tên/email/IP thô. Account/device features chỉ thêm khi pseudonymization, consent, retention khớp P8. Chốt schema, key, retry/DLQ, idempotency, durable publish trước implementation.

### product.event-cancelled
Consumer Order idempotent. Event lặp không nhả tồn nhiều lần. Hủy/nhả Order chưa thanh toán; Order đã thanh toán theo refund P4.

## 6. Ma trận lỗi

| Trường hợp | HTTP | Error |
|---|---:|---|
| Payload/quantity sai | 400 | 4007 hoặc Common validation |
| Chưa xác thực | 401 | Common/Gateway auth |
| Sai role | 403 | Common auth |
| Order không có/không thuộc buyer | 404 | 4001 |
| Event không được bán/đã hủy | 409 | 4002 |
| Item không có tồn | 409 | 4006 |
| Hold hết hạn | 409 | 4004 |
| Sai state | 409 | 4005 |
| Inventory projection chưa sẵn sàng | 503 | 4009 |
| Product/Kafka unavailable | 502/503 | Common/service error |

## 7. Cần hoàn thiện khi triển khai

Giới hạn số vé tối đa mỗi request, pagination/filter cho danh sách Order, DLQ/alerting outbox, late payment/refund P4 và đối chiếu mã lỗi Common.
