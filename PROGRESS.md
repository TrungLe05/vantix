# Vantix — Tiến độ

> Cập nhật: 14/11/2026 · nhánh `feature/payment-service` (P4). Trạng thái lấy từ code, không chỉ từ docs.

## Tổng quan

| Phase | Nội dung | Trạng thái | Ghi chú |
|---|---|---|---|
| P0 | Hạ tầng, khung 8 service, CI | ✅ Xong | Compose tự tạo 5 DB khi volume trống (init script); `docker-compose.prod.yml`, `Dockerfile` gốc rỗng |
| P1 | User & Auth | ✅ Xong | 14 API, manual test; chưa có test tự động |
| P2 | Product (catalog) | ✅ Xong phần API | 14 API (PR01–PR14); thiếu test, bootstrap DB |
| P3 | Order: giỏ, hold, chống bán trùng | ✅ Xong | 9 API + UI (có hủy giỏ CART); integration/concurrency test, E2E Playwright, k6 (đúng đắn đạt, thông lượng/event còn thấp) |
| P4 | Payment (Stripe) | 🟡 Đã code + integration test | Stripe Checkout, webhook, hoàn tiền, UI; chưa chạy với Stripe thật, chưa có E2E |
| P5 | Notification & check-in | ⬜ Chưa bắt đầu | notification-service khung rỗng → email OTP/STAFF chưa thực sự gửi |
| P6 | Bot-detection | ⬜ Chưa bắt đầu | Chỉ `/health`; topic `cart.item_added` đã có producer |
| P7 | AI Assistant | ⬜ Chưa bắt đầu | Chỉ `/health` |
| P8 | Dữ liệu cá nhân | ⬜ Chưa bắt đầu | |
| P9 | Hoàn thiện, deploy | ⬜ Chưa bắt đầu | |

## Chi tiết đã có trong code

### P1 — User & Auth
- `user-service`: register, verify-email, resend-verification, login, Google OAuth2 (`oauth2Login`) + exchange, refresh (rotation + reuse detection), logout, forgot/reset/change password, `GET /users/me`, `POST /users/staff` (ADMIN).
- OTP ở Redis (HMAC), refresh token JWT trong cookie httpOnly, claim `mcp` ép đổi mật khẩu.
- Seed: 1 ADMIN, 1 STAFF, 5 BUYER (`buyer0..4`).
- Gateway: `JwtAuthenticationGlobalFilter`, public-endpoints, password-change allowlist, CORS.
- Kafka producer 3 topic `user.*` (chưa có consumer).
- Frontend: login/register/verify/forgot/reset/auth-callback, onboarding Google lần đầu, profile.

### P2 — Product
- Venue (tạo, liệt kê), Event (tạo, sửa, publish có gom lý do, hủy, danh sách công khai, chi tiết, danh sách ADMIN), Seat map + thêm ghế theo lô, Ticket type SEATED/GA.
- Migration V1 schema, V2 seed event, V3 `event_outbox` (hủy event phát qua outbox).
- Seat map trả `status` ghế `ACTIVE/INACTIVE`.
- Frontend: discovery, event detail, seat map; admin events (list/new/detail/edit) và venues với dữ liệu thật.

### P3 — Order
- API: OR01 tạo/reuse giỏ + hold, OR02 danh sách, OR03 chi tiết, OR04 thêm items, OR05 bỏ item, OR05A sửa quantity GA, OR06 confirm → `PENDING_PAYMENT`, OR07 availability public, OR08 hủy giỏ.
- Chống bán trùng: Idempotency-Key (`idempotency_keys`), Redis `SET NX` + TTL, partial unique index ghế/CART, CHECK tồn GA, update có điều kiện.
- Hết hạn: scheduler 1 giây + expire khi đọc.
- Kafka: outbox `cart.item_added`; consumer `product.event-cancelled` idempotent (`cancelled_events`).
- OpenFeign gọi Product, fail closed (4010).
- Script k6 `backend/services/order-service/k6/seat-contention.js` (chưa có kết quả chạy lưu lại).
- Test: `OrderRequestHasherTest` (unit duy nhất của toàn repo).
- Frontend: Continue tạo hold từ event detail/seat map, trang orders (list) và order detail (countdown, sửa GA, bỏ item, confirm), không giả lập payment.

### P4 — Payment
- payment-service: `POST /internal/payments/checkout-sessions` (nội bộ, Order gọi), `POST /payments/webhooks/stripe` (công khai, xác thực chữ ký), consumer `order.refund-requested`, outbox `payment_event_outbox`, scheduler expire phiên theo deadline hold và retry refund. Stripe sau interface `StripeGateway` (stripe-java 34.1.0).
- order-service: OR09 checkout, OR10 refund-quote, OR11 refund; consumer `payment.succeeded`/`payment.failed`/`refund.completed`/`refund.failed`; trạng thái `REFUND_PENDING`/`REFUNDED`, bảng `order_refunds` (migration V3); OR08 chỉ còn cho CART; hủy event hoàn 100% đơn đã thanh toán.
- Gateway: webhook Stripe vào `public-endpoints`.
- Frontend: nút "Pay" (chuyển hướng Stripe Checkout), chờ xác nhận sau khi quay về, hộp thoại hoàn tiền có xem trước số tiền, mục Purchases ở trang orders.
- Test: payment 19 case, order thêm 20 case (15 thanh toán/hoàn, 4 chính sách, 1 outbox); tổng order 75.
- Quyết định mới D27–D30 (`DECISIONS.md`).

## Đồng bộ tài liệu (13/11/2026)

Đã đối chiếu docs API với code và sửa các điểm lệch:
- `docs/order/P3_order_api_design.md`: thêm OR08 `DELETE /orders/{id}`, shape item/availability, quy tắc Idempotency-Key, payload thực tế `cart.item_added`, khóa Redis, mã lỗi 4003/4008/4010/4011.
- `docs/product/P2_product_api_design.md`: PR06 dùng transactional outbox (không còn `afterCommit`), PR11 có `status` ghế, PR08 có `createdAt`, message publish/cancel.
- `docs/auth/P1_auth_api_design.md`: seed 5 BUYER, giá trị `oauth:code` gồm `isNewUser`, ghi chú public-endpoints từ P2/P3.
- `docs/CONTEXT.md`: tên DB đúng, OTP ở Redis, tiến độ.
- `docs/00_roadmap_phases_v1.md`: Spring Boot 4.1.x.
- `CLAUDE.md`: vị trí docs P3, tiến độ, cơ chế outbox.

Đợt 2 (13/11/2026): đồng bộ `AGENTS.md`, viết lại `docs/auth/P1_auth_design.md` theo implementation, cập nhật `.env.example` + thêm `frontend/.env.example` (trước đó cả hai bị `.gitignore` chặn nên chưa có trong repo).

## Rủi ro / nợ kỹ thuật

- ~140 test tự động backend (unit + integration trên Postgres/Redis thật, CI dùng service container); E2E Playwright cho P1–P3, chưa có cho thanh toán.
- P4 chưa chạy với Stripe thật: lớp gọi SDK (`StripeSdkGateway`) mới được biên dịch, logic được test với Stripe giả lập.
- Refund `FAILED` phải xử lý tay (log `REFUND_FAILED`); chưa có API quản trị.
- Outbox Product/Order đã có backoff + dead-letter + gauge (D25), chưa có rule alert (P9); user-service vẫn có thể mất event Kafka (không dùng outbox).
- Đã gỡ log nhạy cảm (fingerprint JWT secret, message DB chứa giá trị vi phạm, message JJWT, message lỗi OAuth2); secret có thể đã xuất hiện trong log dev cũ.
- Cổng service còn truy cập trực tiếp được ở dev (Trust-The-Gateway cần network isolation ở P9).

## Đợt xử lý backlog P0–P3 (13/11/2026)

Backlog có trạng thái/PR ở `TASKS.md`; quyết định mới D20–D26 ở `DECISIONS.md`.

- #27 `chore/database-bootstrap` (merged): init script tạo 5 DB. Kiểm chứng: volume mới chạy script tạo đủ 5 DB (log `CREATE DATABASE` ×5); chạy lại trên container compose `postgres` đang có dữ liệu → no-op, exit 0; probe nhánh tạo bằng DB tạm rồi drop.
- #28 `test/gateway-auth-cors` (merged): log gateway mặc định `INFO`, `DEBUG` theo profile `dev`; integration test gateway (Netty thật + HTTP stub phía sau) 12 case: preflight CORS, 9001/9002/9003, refresh token bị từ chối làm access token, inject/xóa `X-User-*`, allowlist mcp. `mvn clean verify` toàn backend: BUILD SUCCESS.
- #29 `fix/user-otp-atomic` (merged): OTP verify bằng Lua, cooldown `SET NX` (D22); integration test user-service trên Postgres/Redis thật (15 case: OTP giới hạn/dùng một lần/cooldown kể cả song song, refresh rotation/reuse/song song). CI backend có service container Postgres + Redis và bước tạo DB test.
- #30 `fix/product-publish-seat-constraints` (merged): integration test product-service qua HTTP (MockMvc + security filter thật) trên Postgres thật, 12 case. Sửa: PR10 `saveAllAndFlush` để trùng ghế đã có trả 3203 (trước là 1005); PR12 gán ghế bằng UPDATE có điều kiện (trước: 8 request song song cùng ghế → 7 thành công); PR12 trả 1001 khi gửi field sai category, gộp `seatIds` lặp.
- #31 `test/order-concurrency-idempotency` (merged): 39 test mới cho order-service (14 unit `CatalogValidationService`, 12 vòng đời/chuyển trạng thái, 13 concurrency) trên Postgres/Redis thật, product mock. Sửa 2 lỗi test phát hiện khi expire song song: 500 do optimistic lock và deadlock Hold↔Order (D23). Chạy lặp 6 lần liên tiếp đều pass.
- #32 `feat/order-list-paging-ticket-limit` (merged): OR02 phân trang + lọc status (`PageResponse`), giới hạn 10 vé/Order (`4012`, D24); frontend `getOrders` đọc `result.content`. 8 integration test mới.
- #33 `feat/outbox-backoff-dead-letter` (merged): backoff lũy thừa + dead-letter + gauge cho `order_event_outbox`/`event_outbox` (migration order V2, product V4), producer `max.block.ms` 10s. Sửa lỗi publisher product đọc `cancelledAt` → trước đây hủy event làm cả outbox kẹt. 11 integration test outbox mới.
- #34 `docs/order-or08-k6-readiness` (merged): đưa `k6/seat-contention.js` vào repo (trước bị `.gitignore`), sửa threshold GA, thêm `invariants.sql` (5 bất biến + expiry lag, đã chạy thử trên DB test) và README chạy/báo cáo; khuyến nghị OR08 (D26).
- #35 `test/e2e-playwright` (merged): Playwright E2E ADMIN/BUYER trên stack riêng. Sửa 2 lỗi chỉ lộ khi chạy thật: (1) order-service thiếu `spring-boot-starter-kafka` (Boot 4) → `@KafkaListener` không đăng ký, `product.event-cancelled` chưa bao giờ được tiêu thụ (chỉ có lưới an toàn availability); (2) login/auth-callback chuyển tài khoản `mustChangePassword` tới `/change-password` nhưng trang không tồn tại (404, STAFF mới tạo không dùng được). Thêm `OrderEventListenerRegistrationTest` chặn hồi quy.
- #36 (merged): pgAdmin cổng 8081. #37 (merged): nút "Cancel cart" cho CART (D26 chốt), D24 chốt 10 vé/Order.
- Rotate `JWT_SECRET_KEY` trong `backend/.env` local (14/11/2026, không commit) — cần restart user-service + api-gateway, mọi phiên cũ đăng xuất.
- `test/k6-contention-results`: chạy k6 thật (100 VU tranh ghế, 200 VU tranh 30 vé GA) — đúng đắn tuyệt đối, bất biến đạt, expiry lag p95 1.25s; độ trễ giữ vé p95 4.3–7.5s do khóa theo event. Script không còn ghi access token vào summary (bỏ `--summary-export`).
