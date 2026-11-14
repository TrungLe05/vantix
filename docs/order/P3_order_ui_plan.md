# Vantix Frontend — Phase 3: Order UI Plan

> **Trạng thái:** Đã thiết kế UXMagic và nối các luồng seat map/cart với API Order; payment vẫn thuộc P4.
> **Liên quan:** ../P0_frontend_setup.md, ../product/P2_product_ui_plan.md, P3_order_design.md, P3_order_api_design.md.
> **Nguyên tắc:** Theo frontend/AGENTS.md, đối chiếu/tạo thiết kế UXMagic trước khi triển khai UI. Không giả lập API Order.

## 1. Mục tiêu

Thay placeholder “Checkout will be available soon” bằng thao tác tạo hold, giỏ theo event, confirm và trạng thái loading/lỗi/hết hạn. P3 chưa tạo Stripe session; sau confirm UI dừng ở trạng thái bàn giao Payment P4.

## 2. Luồng buyer

~~~mermaid
flowchart TD
    Detail[Event details] -->|Select seats / choose GA quantity| Selection[Local selection]
    Selection -->|Continue| Create[POST Order and create holds]
    Create -->|Success| Cart[Event cart with 10-minute timer]
    Create -->|Conflict| Retry[Explain no items held; refresh selection]
    Cart -->|Change GA quantity / remove item| Cart
    Cart -->|Confirm| Pending[PENDING_PAYMENT handoff]
    Cart -->|Deadline reached| Expired[Expired cart; inventory released]
    Detail -->|Open another event cart| OtherCart[Other event cart]
~~~

- Chọn ghế/GA chỉ là local state cho đến khi Continue; không báo đã giữ vé trước khi API thành công.
- Buyer có thể có giỏ cho nhiều event; mỗi event tối đa một CART. Thêm item không đặt lại deadline.
- Sau API thành công, hiển thị countdown theo holdExpiresAt từ server.
- Nếu all-or-nothing thất bại, báo có lựa chọn không còn trống, refresh availability và yêu cầu chọn lại; không hiển thị item nào là held.
- Không gộp nhiều event vào một Order hoặc tổng thanh toán chung.
- Confirm gọi OR06. Không giả lập thanh toán thành công hay Stripe trong P3.

## 3. Màn hình/component cần thiết kế UXMagic

1. UXMagic frame `Vantix Buyer Order Cart` trong project `Vantix Event Discovery`: event title/date, item/seat labels, snapshot price, subtotal, countdown, GA quantity controls, remove và confirm.
2. Danh sách/chuyển đổi giữa các cart của nhiều event.
3. Banner xung đột seat/GA và trạng thái availability mới.
4. Loading skeleton cho cart fetch/confirm; chống gửi lặp khi request chạy.
5. Expired/cancelled state với hành động quay lại chọn vé.
6. Nút "Cancel cart" (OR08) chỉ khi CART, có hộp xác nhận; nhả vé ngay (D26). PENDING_PAYMENT không có nút (P4).
6. Empty cart với đường dẫn về event/discovery.
7. Confirm handoff cho PENDING_PAYMENT, nêu rõ bước thanh toán sẽ được nối ở P4.

Dùng UXMagic project hiện có với Event Details/Seat Map làm cơ sở. Thiết kế responsive cho desktop/mobile trước khi code.

## 4. Quy tắc dữ liệu và tương tác

- Chỉ API Order thành công mới xác nhận hold; local selection không phải reservation.
- Lưu Order ID/event ID ở route hoặc state; không tin buyer ID phía client.
- Refresh sau add/remove/confirm và khi quay lại cart; server deadline là authoritative.
- Countdown về 0 thì refetch; client không tự quyết định Order state.
- Availability kết hợp seat map Product tĩnh với trạng thái động Order. API public read-only được frontend refetch mỗi 15 giây; không giả định WebSocket trong P3.
- Theo auth flow hiện có khi 401. Với Order không tồn tại/không thuộc buyer, render trạng thái unavailable an toàn.
- Chặn action lặp khi request pending; retry cùng logical request dùng cùng idempotency key.
- Chuyển event không kéo dài deadline cart nào.

## 5. Loading, lỗi và accessibility

- Skeleton giữ bố cục và có accessible loading label.
- Countdown không phụ thuộc riêng màu sắc; thông báo hết hạn có thể được screen reader đọc.
- Conflict banner nêu rõ không item nào được giữ khi all-or-nothing tạo hold thất bại.
- Cart expired/cancelled không cho confirm; trạng thái có text và icon.
- Giữ keyboard focus sau thao tác remove/error; drawer/dialog theo focus và Escape convention hiện có.

## 6. API mapping

| UI action | API |
|---|---|
| Continue từ Event Details/Seat Map | POST /api/orders |
| Load buyer carts | GET /api/orders |
| Mở một cart | GET /api/orders/{orderId} |
| Add item trong cart | POST /api/orders/{orderId}/items |
| Tăng/giảm quantity GA | PATCH /api/orders/{orderId}/items/{itemId} với quantity đích |
| Remove item | DELETE /api/orders/{orderId}/items/{itemId} |
| Confirm | POST /api/orders/{orderId}/confirm |
| Refresh seat/GA | GET /api/orders/events/{eventId}/availability + Product catalog |

DTO, error và refresh cadence theo P3_order_api_design.md khi được hoàn thiện.

## 7. Acceptance checklist

- Continue tạo hold thật; trước khi API thành công, UI không báo đã giữ vé.
- Một item unavailable rollback toàn yêu cầu và UI yêu cầu chọn lại.
- Countdown theo holdExpiresAt, dùng chung cho Order; thêm item không gia hạn.
- Buyer truy cập nhiều giỏ event riêng biệt, không gộp.
- Remove, expiry, event cancellation và confirm hiển thị server state.
- Thay đổi quantity GA cập nhật tồn kho, subtotal và tổng Order cùng transaction; số lượng ghế đặt trước luôn là 1.
- UXMagic có loading/error/empty/conflict/expired/cancelled/PENDING_PAYMENT state; UI giữ các trạng thái này.
- UI P3 dừng ở PENDING_PAYMENT, không có payment giả.
