# Vantix — ngữ cảnh gọn (dán vào đầu chat hoặc đưa vào Claude Project)

> Bản rút gọn của CLAUDE.md/AGENTS.md + docs. Khi quyết định đổi, cập nhật file này cùng các file đó. Bức tranh đầy đủ: `README.md`, `ARCHITECTURE.md`, `DECISIONS.md`, `PROGRESS.md`, `TASKS.md` ở thư mục gốc.

## Dự án
Nền tảng bán vé sự kiện (vé ghế số + GA), chống bán trùng vé khi đồng thời cao, bot-detection (Isolation Forest, dữ liệu tự tạo), trợ lý AI (LLM API), thanh toán Stripe test (USD). Monorepo Maven, 8 service.

## Stack
Java 21, Spring Boot 4.1.x, Spring Cloud 2025.1.2, Lombok 1.18.42, Maven. PostgreSQL + Flyway (DB riêng mỗi service), Redis, Kafka KRaft. Gateway dùng `spring-cloud-starter-gateway-server-webflux`. Python FastAPI cho bot-detection, assistant. Frontend Next.js 16 / React 19. Lưu ý: Boot 4 khác 3.x, không dùng API theo trí nhớ khi chưa chắc.

## Service
| Service | Port | DB |
|---|---|---|
| api-gateway | 8999 | — |
| notification | 9000 | notifications_service |
| order | 9001 | orders_service |
| payment | 9002 | payments_service |
| product | 9003 | products_service |
| user | 9004 | users_service |
| bot-detection | 9005 | — |
| assistant | 9006 | — |

Tên DB lấy từ `application.yaml` của từng service. Service Java có context-path `/api` (controller mapping không thêm `/api`).

## Convention
- Package theo layer: controller / services (interface + Impl) / repository / entities / dto / exception / config (order-service dùng `service`, `entity`, `enums`).
- Mapping Entity/DTO viết tay, không MapStruct. Entity JPA không `@Data`.
- `ApiResponse<T>` {code=200 mặc định, message NON_NULL, result}. Lỗi: enum ErrorCode (code, message, HttpStatus) + `AppException` + `GlobalExceptionHandler`.
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
- P3: một Order = một event; tối đa một CART/buyer/event; hold 10 phút từ hold đầu, thêm item không gia hạn; all-or-nothing.

## Phase
P0 hạ tầng: xong. P1 User & Auth: xong (14 API). P2 Product: xong phần API (14 API), thiếu test + bootstrap DB. P3 Order: đã code (9 API, outbox, consumer hủy event, UI cart), còn chạy E2E + k6. P4 Payment → P5 Notification/check-in → P6 Bot-detection → P7 Assistant → P8 Dữ liệu cá nhân → P9 Hoàn thiện: chưa bắt đầu.

## P1 — tóm tắt thiết kế
- Access/refresh token đều là JWT (claim `token_type`); refresh rotation + reuse detection theo `family_id`, DB lưu SHA-256 hash. Refresh token đi bằng cookie httpOnly `Path=/api/auth`.
- Email chưa xác thực: `PENDING_VERIFICATION`, chặn đăng nhập. OTP lưu ở **Redis** (`otp:{purpose}:{userId}`, HMAC-SHA256, TTL 10 phút, 5 lần sai, cooldown 60s) — bảng `USER_OTP` đã bỏ.
- Google OAuth2 qua `oauth2Login()` của Spring Security; callback 302 về frontend kèm one-time code, đổi lấy token ở `POST /auth/oauth2/exchange`.
- ADMIN tạo STAFF: sinh mật khẩu tạm, gửi email qua Kafka, `must_change_password = true` (claim `mcp`, Gateway chỉ cho gọi allowlist).
- Đổi hoặc reset mật khẩu: thu hồi toàn bộ refresh token của user.
- Bảng: `users` (email unique khi `deleted_at IS NULL`), `refresh_tokens`.
- Kafka: `user.email-verification-requested`, `user.forgot-password-requested`, `user.staff-account-created` (chứa mật khẩu tạm, tuyệt đối không log).
- API chi tiết: `docs/auth/P1_auth_api_design.md`.

## Cách nhờ AI
Nói rõ phase và file liên quan, dán code hiện có. Yêu cầu: plan ngắn trước, code hoàn chỉnh theo convention trên, nêu giả định chưa chắc.
