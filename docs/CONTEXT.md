# Vantix — ngữ cảnh gọn (dán vào đầu chat hoặc đưa vào Claude Project)

> Bản rút gọn của CLAUDE.md/AGENTS.md + docs. Khi quyết định đổi, cập nhật file này cùng các file đó. Bức tranh đầy đủ: `README.md`, `ARCHITECTURE.md`, `DECISIONS.md`, `PROGRESS.md`, `TASKS.md` ở thư mục gốc.

## Dự án
Nền tảng bán vé sự kiện (vé ghế số + GA), chống bán trùng vé khi đồng thời cao, bot-detection (Isolation Forest, dữ liệu tự tạo), trợ lý AI (LLM API), thanh toán Stripe test (USD). Monorepo Maven, 8 service.

## Stack
Java 21, Spring Boot 4.1.x, Spring Cloud 2025.1.2, Lombok 1.18.42, Maven. PostgreSQL + Flyway (DB riêng mỗi service), Redis, Kafka KRaft. Gateway dùng `spring-cloud-starter-gateway-server-webflux`. Python FastAPI cho bot-detection (scikit-learn, aiokafka), assistant (mới có `/health`). Frontend Next.js 16 / React 19. Lưu ý: Boot 4 khác 3.x, không dùng API theo trí nhớ khi chưa chắc.

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
- Bot rủi ro cao: chặn lần thêm giỏ tiếp theo + đánh dấu review, không hủy hold hiện tại. D46: chỉ chặn khi HIGH kèm luật tay (`ruleMatched`) hoặc HIGH lần hai; HIGH đơn lẻ → `WATCH`.
- Hoàn vé: >= 7 ngày 100%, 24h–7 ngày 50%, < 24h 0%, ADMIN hủy sự kiện 100%. Đơn có vé đã check-in chỉ hoàn vé chưa dùng (D43).
- Vai trò: BUYER, ADMIN, STAFF (ADMIN tạo). SELLER để sau.
- P3: một Order = một event; tối đa một CART/buyer/event; hold 10 phút từ hold đầu, thêm item không gia hạn; all-or-nothing.
- P4 (D27–D30): Stripe Checkout Session, hạn thanh toán = deadline hold; tiền vào sau khi Order hết hạn/hủy → hoàn 100%; hoàn tiền qua Kafka `order.refund-requested`; API service-to-service đặt dưới `/internal/**`, không route qua Gateway.
- P5 (D31–D36): vé phát hành cùng transaction `payment.succeeded` (→ `TICKETS_ISSUED`), check-in update nguyên tử (4017 quét trùng), chỉ mở 3 giờ trước → giờ kết thúc (D44, 4021); Notification chỉ nghe event Order, email theo trạng thái bảng `notifications`; WebSocket + ticket một lần; QR sinh ở backend.
- P6 (D37–D42): device ID = UUID ngẫu nhiên ở localStorage, Order chỉ giữ HMAC; Isolation Forest huấn luyện offline trên dữ liệu k6; chặn 4019 theo buyer hoặc thiết bị; ADMIN gỡ flag; chưa có model → `UNSCORED`.
- Venue bắt buộc tọa độ chọn từ OpenStreetMap (D45).

## Phase
P0 hạ tầng, P1 User & Auth, P2 Product, P3 Order: xong (có integration test; P3 có E2E Playwright + k6). P4 Payment, P5 Notification & check-in: đã code + integration test; Stripe test mode đã chạy thật (15/11/2026), P5 đã E2E Kafka + WebSocket + SMTP sink local (chưa SMTP thật có TLS). P6 Bot-detection: đã code + huấn luyện, chưa E2E trình duyệt. P7 Assistant → P8 Dữ liệu cá nhân → P9 Hoàn thiện: chưa bắt đầu. Danh sách quyết định đủ: `DECISIONS.md` (D1–D46).

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
