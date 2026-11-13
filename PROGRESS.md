# Vantix — Tiến độ

> Cập nhật: 13/11/2026 · nhánh `feature/order-service` (commit gần nhất `cd9184d`). Trạng thái lấy từ code, không chỉ từ docs.

## Tổng quan

| Phase | Nội dung | Trạng thái | Ghi chú |
|---|---|---|---|
| P0 | Hạ tầng, khung 8 service, CI | ✅ Xong | Compose chưa tự tạo DB; `docker-compose.prod.yml`, `Dockerfile` gốc rỗng |
| P1 | User & Auth | ✅ Xong | 14 API, manual test; chưa có test tự động |
| P2 | Product (catalog) | ✅ Xong phần API | 14 API (PR01–PR14); thiếu test, bootstrap DB |
| P3 | Order: giỏ, hold, chống bán trùng | 🟡 Đã code | 9 API (OR01–OR08, OR05A) + UI; còn E2E, k6, test |
| P4 | Payment (Stripe) | ⬜ Chưa bắt đầu | payment-service là khung rỗng |
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

- Hầu như chưa có test tự động; CI `mvn verify` hiện chủ yếu kiểm tra biên dịch.
- Chưa chứng minh bằng số liệu chống bán trùng (k6) — đây là điểm trọng tâm của dự án.
- Outbox chưa có DLQ/alerting; user-service vẫn có thể mất event Kafka.
- Đã gỡ log nhạy cảm (fingerprint JWT secret, message DB chứa giá trị vi phạm, message JJWT, message lỗi OAuth2); secret có thể đã xuất hiện trong log dev cũ.
- Cổng service còn truy cập trực tiếp được ở dev (Trust-The-Gateway cần network isolation ở P9).
