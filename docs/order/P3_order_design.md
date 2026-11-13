# Vantix — Phase 3: Order Design

> **Trạng thái:** Thiết kế P3 đã được duyệt và cập nhật theo implementation; kiểm chứng môi trường/k6 còn mở.
> **Liên quan:** ../00_roadmap_phases_v1.md, ../CONTEXT.md, ../product/P2_product_design.md, ../product/P2_product_api_design.md.
> **Phạm vi:** Giỏ hàng, hold SEATED/GA, chống oversell, expiration và hủy event. Payment, refund thực tế, vé/QR, check-in thuộc các phase sau.

## 1. Mục tiêu và nguyên tắc

Order là nguồn dữ liệu giao dịch cho giỏ, hold và tồn kho bán. Product sở hữu catalog (event, ticket type, seat map, ghế và số lượng gốc), không xử lý hold.

Ba lớp chống bán trùng đều cần có:
1. Idempotency key ngăn retry tạo giao dịch lặp.
2. Redis TTL hỗ trợ nhận biết/giải phóng hold hết hạn nhanh.
3. PostgreSQL Order là lớp cuối: ràng buộc ghế và cập nhật nguyên tử số lượng GA.

Redis không phải nguồn quyết định cuối về khả năng bán; xác nhận tồn phải dựa trên transaction/ràng buộc DB Order.

## 2. Quyết định nghiệp vụ đã chốt

- Một Order chỉ chứa vé của một event; có thể có nhiều ticket type và cả SEATED lẫn GA.
- Mỗi BUYER có tối đa một Order CART cho mỗi event; có thể có giỏ CART cho nhiều event đồng thời.
- Buyer chọn ghế/số lượng ở frontend trước. Khi bấm Continue, Order nhận yêu cầu và tạo hold. Chỉ sau khi hold thành công mới bắt đầu đếm giờ.
- Mọi hold trong một Order dùng chung hold_expires_at: **10 phút kể từ hold đầu tiên**. Thêm item không gia hạn deadline.
- Tạo hold nhiều item theo **all-or-nothing**. Nếu một item không khả dụng thì rollback toàn bộ yêu cầu.
- Order sở hữu tồn kho giao dịch. GA được theo dõi bằng số lượng gốc, đang giữ và đã bán trong DB Order. Ghế tĩnh lấy từ Product; trạng thái giữ/bán lấy từ Order.
- Confirm giỏ chuyển CART sang PENDING_PAYMENT. Tích hợp Payment thuộc P4.
- Khi event bị hủy, Order chặn hold mới, giải phóng hold chưa thanh toán và chuyển Order đó sang CANCELLED. Vé đã thanh toán được hoàn 100% theo FR4; Payment thực hiện ở phase thanh toán.
- Consumer hủy event phải idempotent. Order kiểm tra trạng thái event khi tạo/xác nhận hold để giảm race với thông báo hủy.
- P3 phát Kafka event khi thêm item để P6 xử lý bot-detection. Payload không chứa tên, email hoặc IP thô; schema tuân thủ privacy requirements P8.
- `product.event-cancelled` và `cart.item_added` được ghi vào transactional outbox cùng transaction nghiệp vụ; publisher retry nếu Kafka chưa nhận. Consumer hủy event idempotent vì bản tin có thể được phát lại.

## 3. Phạm vi

### Trong P3
- Tạo/reuse giỏ cho BUYER và một event; giữ/bỏ ghế SEATED hoặc số lượng GA.
- Đọc giỏ, deadline, availability; confirm Order trước deadline.
- Giải phóng hold hết hạn bằng Redis TTL và job đối soát DB.
- Tiêu thụ product.event-cancelled, chặn đặt mới và hủy/nhả Order chưa thanh toán.
- Phát Kafka event khi thêm giỏ; consumer bot-detection thuộc P6.
- Kiểm chứng concurrency bằng test và k6.
- UI Seat Map đọc availability từ Order, tạo hold khi Continue và hiển thị cart riêng theo event; thanh toán chưa thực hiện trong P3.

### Ngoài P3
- Stripe session, webhook, hoàn tiền thực tế, payment success/failure (P4).
- Ticket/QR, chuyển nhượng, check-in.
- Bot scoring và xử lý rủi ro (P6). Rủi ro cao chỉ chặn lần thêm giỏ sau và đánh dấu review, không hủy hold hiện tại.
- WebSocket notification (P5).

## 4. Mô hình dữ liệu đề xuất

~~~mermaid
erDiagram
    ORDERS ||--|{ ORDER_ITEMS : contains
    ORDERS ||--|{ HOLDS : reserves
    ORDER_ITEMS ||--o{ HOLDS : has
    TICKET_INVENTORY ||--o{ HOLDS : allocates
    ORDERS {
        uuid id PK
        uuid buyer_id
        uuid event_id
        string status
        decimal total_amount
        string currency
        timestamp hold_expires_at
        timestamp created_at
        timestamp updated_at
    }
    ORDER_ITEMS {
        uuid id PK
        uuid order_id FK
        uuid ticket_type_id
        uuid seat_id "nullable for GA"
        string ticket_name_snapshot
        string category_snapshot
        decimal unit_price_snapshot
        int quantity
        decimal subtotal
    }
    HOLDS {
        uuid id PK
        uuid order_id FK
        uuid order_item_id FK
        uuid ticket_type_id
        uuid seat_id "nullable for GA"
        int quantity
        string status
        timestamp expires_at
        timestamp created_at
    }
    TICKET_INVENTORY {
        uuid ticket_type_id PK
        uuid event_id
        int total_quantity
        int held_quantity
        int sold_quantity
        timestamp updated_at
    }
~~~

Đây là logical model; kiểu cột/index cuối cùng cần đối chiếu convention và PostgreSQL khi tạo migration.

### Quy tắc dữ liệu
- buyer_id lấy từ X-User-Id do Gateway xác thực; không nhận buyer ID từ client làm căn cứ phân quyền.
- OrderItem lưu ticket type/name/category/price snapshot để catalog thay đổi không làm đổi giá Order.
- Currency là USD; tổng tính từ snapshot phía server.
- SEATED: mỗi item là một seat, quantity=1. GA: ticket type và quantity dương. Trong một Order, mỗi ticket type GA chỉ có một OrderItem/active Hold; các lần thêm sau gộp vào quantity hiện tại và dùng price snapshot ban đầu của item.
- Hold cùng Order dùng deadline chung.
- Tồn GA khả dụng = total_quantity - held_quantity - sold_quantity; không cho held/sold vượt tổng.
- Payment thành công ở P4 chuyển quantity từ held sang sold. Nhả/expiry chỉ giảm held. Mọi chuyển đổi idempotent.
- Unique constraint có điều kiện ngăn nhiều hold hiệu lực cho cùng seat_id.
- Một CART mỗi buyer/event cần được bảo đảm dưới concurrency, không chỉ kiểm tra ở Java.

### Điểm đã triển khai và còn giới hạn
- Order khởi tạo GA inventory từ `totalQuantity` Product; nếu catalog thay đổi số lượng sau lần khởi tạo thì Order fail closed để tránh âm thầm lệch projection.
- Redis TTL xử lý nhanh và job DB dọn hold theo deadline; DB là nguồn quyết định khi lệch.
- Hai luồng Kafka dùng transactional outbox và retry; consumer idempotent để xử lý trường hợp phát lặp.
- Availability là public read-only qua Gateway; UI refetch mỗi 15 giây và luôn dùng API tạo hold để quyết định cuối.
- Payment thành công sau deadline hold được xử lý trong hợp đồng P4.

## 5. State machine

### Order

~~~mermaid
stateDiagram-v2
    [*] --> CART
    CART --> PENDING_PAYMENT: buyer confirms before deadline
    CART --> EXPIRED: common hold deadline reached
    CART --> CANCELLED: buyer cancels or event is cancelled
    PENDING_PAYMENT --> PAID: payment success (P4)
    PENDING_PAYMENT --> EXPIRED: payment deadline/hold expires
    PENDING_PAYMENT --> CANCELLED: event cancellation or payment failure policy
    PAID --> TICKETS_ISSUED: issue tickets (later phase)
~~~

P3 triển khai CART, PENDING_PAYMENT, EXPIRED, CANCELLED. Payment/phát hành vé được thể hiện để giữ state machine xuyên phase.

### Hold

~~~mermaid
stateDiagram-v2
    [*] --> HELD: inventory reserved
    HELD --> CONFIRMED: order confirmed
    HELD --> RELEASED: buyer removes item / order cancelled
    HELD --> EXPIRED: deadline reached
    CONFIRMED --> RELEASED: payment failure or event cancellation
    CONFIRMED --> EXPIRED: payment deadline expires
    CONFIRMED --> PURCHASED: payment succeeds (P4)
~~~

PURCHASED là trạng thái đề xuất để biểu diễn hold chuyển thành bán; roadmap hiện liệt kê HELD/EXPIRED/RELEASED/CONFIRMED. Chốt trạng thái persistence khi hoàn thiện P4.

### Hủy event
1. Order nhận product.event-cancelled và xử lý idempotent.
2. Từ chối tạo hold mới và confirm Order cho event đã hủy.
3. Trong transaction, nhả inventory/seat hold chưa thanh toán đúng một lần và chuyển Order sang CANCELLED.
4. Order đã thanh toán được đưa vào luồng hoàn 100% của Payment theo P4.

Product và Order dùng DB riêng nên consumer có eventual consistency. API cần fail closed khi không xác minh được điều kiện bán từ Product.

## 6. Hết hạn và concurrency

- Redis TTL là tín hiệu nhanh; job định kỳ tại Order quét hold DB quá hạn làm lớp dự phòng.
- Expiry chỉ đổi trạng thái hold còn hiệu lực; chỉ transaction đổi trạng thái thành công mới trả tồn. Retry không trả tồn lần hai.
- Seat: unique constraint active hold/sale là lớp cuối chống double hold.
- GA: conditional atomic update/row lock chỉ tăng held khi held + sold + requested <= total_quantity.
- Tạo nhiều item áp dụng all-or-nothing trong một transaction.
- Redis lỗi không được khiến DB chấp nhận oversell; hành vi fail-safe mô tả trong API design.

## 7. Kafka

- Order phát `cart.item_added` khi buyer tạo/thêm item; P6 consume để phân tích bot.
- Order consume product.event-cancelled để hủy/nhả Order chưa thanh toán.
- Consumer idempotent. Payload không có email, tên hoặc IP thô.
- Schema/key được triển khai; transactional outbox retry lỗi publish. Consumer downstream phải idempotent vì Kafka có thể nhận thành công trước khi DB đánh dấu published.

## 8. Kiểm chứng và tiêu chí hoàn thành

- Buyer chỉ đọc/thay đổi Order của chính mình.
- Một Order không chứa nhiều event; mỗi buyer tối đa một CART cho event.
- Request cạnh tranh cùng ghế chỉ có tối đa một hold thành công; GA không vượt total_quantity.
- Một item không khả dụng rollback toàn payload; retry cùng key không tạo hold lặp.
- Expiry, remove, cancel event và retry trả tồn đúng một lần.
- Thêm item không reset deadline; confirm sau deadline bị từ chối.
- K6 cạnh tranh cùng seat ID và GA; lưu cấu hình tải, commit, môi trường, concurrency, invariants cuối, expiry lag và tồn sau giải phóng.
- Còn cần chạy PostgreSQL/Redis/Kafka end-to-end và k6 để ghi bằng chứng chống giữ ghế trùng, kiểm tra GA và expiry lag.
