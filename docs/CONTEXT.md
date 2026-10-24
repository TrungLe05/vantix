# Vantix — ngữ cảnh gọn (dán vào đầu chat hoặc đưa vào Claude Project)

> Bản rút gọn của AGENTS.md + docs. Khi quyết định đổi, cập nhật file này cùng AGENTS.md.

## Dự án
Nền tảng bán vé sự kiện (vé ghế số + GA), chống bán trùng vé khi đồng thời cao, bot-detection (Isolation Forest, dữ liệu tự tạo), trợ lý AI (LLM API), thanh toán Stripe test (USD). Monorepo Maven, 8 service.

## Stack
Java 21, Spring Boot 4.1.x, Spring Cloud 2025.1.2, Lombok 1.18.42, Maven. PostgreSQL + Flyway (DB riêng mỗi service), Redis, Kafka KRaft. Gateway dùng `spring-cloud-starter-gateway-server-webflux`. Python FastAPI cho bot-detection, assistant. Lưu ý: Boot 4 khác 3.x, không dùng API theo trí nhớ khi chưa chắc.

## Service
| Service | Port | DB |
|---|---|---|
| api-gateway | 8999 | — |
| notification | 9000 | notification_service |
| order | 9001 | order_service |
| payment | 9002 | payment_service |
| product | 9003 | product_service |
| user | 9004 | user_service |
| bot-detection | 9005 | — |
| assistant | 9006 | — |

Service Java có context-path `/api` (controller mapping không thêm `/api`).

## Convention
- Package: controller / service / repository / dto / entity / exception / config.
- Mapping Entity/DTO viết tay, không MapStruct. Entity JPA không `@Data`.
- `ApiResponse<T>` {code=200 mặc định, message NON_NULL, result}. Lỗi: enum ErrorCode (code, message, HttpStatus) + exception nghiệp vụ + `GlobalExceptionHandler`.
- Flyway `V{n}__{desc}.sql`, không sửa migration cũ, `ddl-auto: validate`.
- Gateway verify JWT, inject `X-User-Id`/`X-User-Role`; service dùng `GatewayHeaderAuthFilter`, không tự verify JWT.
- Common-lib chỉ khi >= 3 service cần.
- Comment tiếng Việt, giải thích "tại sao".

## Quyết định đã chốt
- Payment không biết chi tiết vé; Order sở hữu đơn, hold, vé, check-in, tính % hoàn.
- Chống bán trùng: idempotency key + Redis TTL + ràng buộc/update nguyên tử ở DB Order (đủ cả ba).
- Order -> Payment sync (tạo phiên); Stripe -> Payment webhook idempotent; Payment -> Order qua Kafka; Order -> bot-detection qua Kafka.
- Bot rủi ro cao: chặn lần thêm giỏ tiếp theo + đánh dấu review, không hủy hold hiện tại.
- Hoàn vé: >= 7 ngày 100%, 24h–7 ngày 50%, < 24h 0%, ADMIN hủy sự kiện 100%.
- Vai trò: BUYER, ADMIN, STAFF (ADMIN tạo). SELLER để sau.

## Phase
P0 hạ tầng: xong. P1 User & Auth: đang làm. Sau đó P2 Product → P3 Order → P4 Payment → P5 Notification/check-in → P6 Bot-detection → P7 Assistant → P8 Dữ liệu cá nhân → P9 Hoàn thiện.

## P1 — tóm tắt thiết kế
- Refresh token: rotation + reuse detection theo `family_id`; lưu SHA-256 hash. Reuse bị phát hiện thì revoke cả family.
- Email chưa xác thực: `PENDING_VERIFICATION`, chặn đăng nhập. OTP lưu bảng `USER_OTP` (hash, attempt_count, expires_at), không dùng Redis.
- Google OAuth2 xử lý trong user-service, tự phát hành JWT hệ thống; tài khoản Google coi như đã xác thực.
- ADMIN tạo STAFF: sinh mật khẩu tạm, gửi email qua Kafka, `must_change_password = true`.
- Đổi hoặc reset mật khẩu: thu hồi toàn bộ refresh token của user.
- Bảng: `USERS` (email unique khi `deleted_at IS NULL` → partial unique index), `REFRESH_TOKENS`, `USER_OTP`.
- Kafka: `user.email-verification-requested`, `user.forgot-password-requested`, `user.staff-account-created` (chứa mật khẩu tạm, tuyệt đối không log).
- API: `/auth/{register, verify-email, login, refresh, logout, forgot-password, reset-password, change-password}`, `/auth/oauth2/{google, callback}`, `GET /users/me`, `POST /users/staff` (ADMIN).

## Cách nhờ AI
Nói rõ phase và file liên quan, dán code hiện có. Yêu cầu: plan ngắn trước, code hoàn chỉnh theo convention trên, nêu giả định chưa chắc.
