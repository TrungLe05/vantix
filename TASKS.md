# Vantix — Danh sách công việc

> Cập nhật: 13/11/2026. Ưu tiên: 🔴 cao (chặn phase hiện tại) · 🟠 trung bình · ⚪ thấp. Đánh dấu `[x]` khi xong và cập nhật `PROGRESS.md`.

## Backlog P0–P3 (đối chiếu code 13/11/2026)

Trạng thái: `TODO` · `IN_PROGRESS` · `IN_REVIEW` · `BLOCKED` · `DONE`. Môi trường kiểm chứng local: container của `docker compose up -d` (project `vantix`), **không** pull image/tạo container mới — nên Testcontainers và k6 qua Docker không chạy local (xem D21).

| # | Hạng mục | Ưu tiên | Trạng thái | PR | Ghi chú |
|---|---|---|---|---|---|
| A1 | Init script tạo 5 DB trong Compose | 🔴 | DONE | #27 | `docker/postgres/init/01-create-databases.sql`, idempotent, chỉ tự chạy khi volume trống |
| A2 | Service kết nối đúng DB (Flyway chạy) | 🔴 | DONE | #29, #30, #31 | Flyway của user/product/order chạy trên DB test cùng Postgres compose; DB dev tên đúng `application.yaml` (đã có sẵn trên container) |
| D1 | Gỡ log fingerprint JWT secret (`JwtUtils`) | 🔴 | DONE | trước đợt này | Chỉ còn log TTL |
| D2 | Log `DataIntegrityViolation`, token bị từ chối, lỗi OAuth2 chỉ còn loại/mã lỗi | 🔴 | DONE | trước đợt này | Đã rà `BaseGlobalExceptionHandler`, `JwtAuthenticationGlobalFilter`, `OAuth2LoginFailureHandler` |
| D3 | Đánh giá rotate `JWT_SECRET_KEY` (fingerprint từng bị log) | 🟠 | DONE | — (local) | 14/11/2026 theo yêu cầu chủ repo: sinh secret mới 64 byte trong `backend/.env` (không in, không commit). Cần restart user-service + api-gateway; mọi phiên cũ bị đăng xuất |
| E1 | `.env.example` khớp biến code, `frontend/.env.example`, bỏ ignore `.env.example` | 🟠 | DONE | trước đợt này | |
| F1 | Gateway log `DEBUG` → `INFO` mặc định, `DEBUG` theo profile `dev` | ⚪ | DONE | #28 | `SPRING_PROFILES_ACTIVE=dev` để bật DEBUG |
| C5 | Test mcp allowlist ở Gateway | 🟠 | DONE | #28 | `GatewayAuthAndCorsIntegrationTest`: chặn ngoài allowlist (9004), cho phép đúng method+path |
| C6 | Test preflight `OPTIONS` qua Gateway | ⚪ | DONE | #28 | Preflight route bảo vệ trả 200 + CORS header không cần token; origin lạ 403 |
| C1 | Test refresh rotation + reuse detection | 🟠 | DONE | #29 | `RefreshTokenRotationIntegrationTest` (Postgres thật): rotation, reuse thu hồi family, family khác không ảnh hưởng, 10 refresh song song → 1 thắng |
| C3 | Test OTP attempts/cooldown | 🟠 | DONE | #29 | `OtpServiceIntegrationTest` (Redis thật) |
| G1 | OTP nguyên tử (`INCR`/Lua) + test concurrency | 🟠 | DONE | #29 | Lua script cho verify, `SET NX` cho cooldown (D22). Code cũ fail 2 test song song |
| B1 | Test product: publish gom lý do, PR10 all-or-nothing, PR12 seat, ẩn DRAFT | 🟠 | DONE | #30 | `ProductCatalogIntegrationTest` 12 case; sửa 3 lỗi test phát hiện: PR10 trả 1005 thay 3203, PR12 race gán ghế (7/8 request cùng thắng), PR12 không chặn field sai category |
| P3-D1 | Unit test `CatalogValidationService`, `OrderCommandService` | 🟠 | DONE | #31 | `CatalogValidationServiceTest` (14, Mockito); state transition của `OrderCommandService` test ở mức integration (`OrderLifecycleIntegrationTest`, 12) vì phụ thuộc khóa/SQL |
| P3-D2 | Giới hạn số vé tối đa mỗi request/Order (cấu hình được) | 🟠 | DONE | #32 | Mặc định 10/Order, lỗi `4012` (D24, đã xác nhận) |
| P3-D3 | Phân trang + lọc status `GET /orders` | 🟠 | DONE | #32 | Đổi shape sang `PageResponse`; đã cập nhật `frontend/src/lib/api/orders.ts` cùng PR |
| P3-C | Integration/concurrency order: tranh ghế, tranh GA, idempotency replay/conflict, expire đúng một lần, consumer hủy event lặp | 🔴 | DONE | #31 | `OrderConcurrencyIntegrationTest` (13); sửa optimistic-lock 500 và deadlock khi expire song song (D23) |
| P3-A | E2E vòng đời đơn: hold → thêm → sửa GA → confirm → hết hạn → hủy event | 🔴 | DONE | #35, #37 | Playwright `frontend/e2e` (17 test + 1 cần tài khoản mcp) trên stack thật Gateway/user/product/order/Kafka với DB `*_e2e`. Phát hiện + sửa: consumer Kafka order-service chưa từng chạy (thiếu `spring-boot-starter-kafka`), route `/change-password` không tồn tại. Hết hạn 10 phút không chạy qua UI (đã phủ ở integration test) |
| P3-B | k6 `seat-contention.js` + kết quả trong `k6/results/` | 🔴 | DONE | test/k6-contention-results | `results/2026-11-14-contention.md`: ghế 100 VU → 1/99, GA 200 VU/30 vé → 30/170, 0 lỗi, 5 bất biến đạt, expiry lag p95 1.25s. Độ trễ cao (p95 4–7s) do khóa event tuần tự hóa — gợi ý tối ưu trong báo cáo |
| P3-D4 | DLQ/alerting outbox, backoff thay chu kỳ cố định | 🟠 | DONE | #33 | D25; sửa kèm lỗi publisher product không parse được `cancelledAt` (outbox kẹt khi có event bị hủy). Rule alert Prometheus chờ P9 |
| P3-D5 | Đánh giá dùng OR08 (hủy giỏ) ở frontend | ⚪ | DONE | #34, feat/order-cancel-cart | D26 đã chốt: nút "Cancel cart" chỉ cho CART; PENDING_PAYMENT xem lại ở P4 |

## Tài liệu

- [x] Cập nhật `AGENTS.md`: tên DB, cấu trúc `backend/`, package, phase, golden example (13/11/2026).
- [x] Cập nhật `docs/auth/P1_auth_design.md`: ERD bỏ `USER_OTP`/`expires_at`, sequence OTP Redis, cookie, `oauth2Login` + exchange (13/11/2026).
- [ ] ⚪ Thay `frontend/README.md` (template create-next-app) bằng hướng dẫn thật.
- [ ] ⚪ Viết `01_system_architecture.md` hoặc trỏ hẳn sang `ARCHITECTURE.md` trong roadmap.

## P4 — Payment

Nhánh `feature/payment-service` (14/11/2026). Kiểm chứng: integration test trên Postgres/Redis thật với Stripe/Kafka giả lập; **chưa chạy với Stripe thật**.

- [x] Thiết kế `docs/payment/P4_payment_design.md`, `P4_payment_api_design.md`, `P4_payment_ui_plan.md` (ERD, sequence thanh toán/hoàn, schema 5 topic).
- [x] Chốt payment thành công đến sau deadline hold → hoàn 100% (D28); đồng bộ deadline bằng scheduler expire phiên Stripe (D27).
- [x] Stripe Checkout Session (test), khởi tạo idempotent theo `order_id` + Stripe Idempotency-Key.
- [x] Webhook xác thực chữ ký, idempotent theo Stripe event id.
- [x] Order consumer: thành công → `PAID`, hold `PURCHASED`, held → sold; thất bại → nhả hold.
- [x] Refund: Order tính % theo 3 mốc, Payment gọi Refund API; hủy event → hoàn 100% (D29).
- [x] OR08 backend chỉ còn nhận CART (việc treo từ D26).
- [x] Frontend: nút thanh toán sau `PENDING_PAYMENT`, hộp thoại hoàn tiền, mục Purchases.
- [ ] 🔴 Chạy thật với Stripe test mode (`stripe listen`, thẻ 4242) cho cả luồng thanh toán, hết hạn và hoàn tiền; ghi kết quả.
- [ ] 🟠 E2E Playwright cho luồng thanh toán/hoàn (cần Stripe test mode hoặc stub webhook) và kiểm tra UI trên trình duyệt.
- [ ] 🟠 Quyết định có cấp "cửa sổ thanh toán" riêng khi confirm sát deadline hay giữ nguyên (D27).
- [ ] ⚪ Đơn 0 đồng (vé miễn phí); hoàn từng vé; API ADMIN xử lý refund `FAILED`; giới hạn vé theo buyer qua nhiều Order (D24).

## P5 — Notification & check-in

- [ ] notification-service consume `user.*` (gửi OTP, reset, mật khẩu tạm STAFF — không log payload), sự kiện Order/Payment.
- [ ] Bảng `notifications`, API danh sách/đánh dấu đã đọc, WebSocket.
- [ ] Order: phát hành vé/QR (`TICKETS_ISSUED`), API quét vé ADMIN/STAFF, chống quét trùng (trả ai quét, lúc nào).

## P6 — Bot-detection

- [ ] Chốt schema `cart.item_added` cho feature (hash device fingerprint, consent P8) và topic kết quả `bot_score.evaluated`.
- [ ] k6 mô phỏng bot + thu log thật; feature engineering; huấn luyện Isolation Forest; notebook đánh giá trung thực.
- [ ] FastAPI consumer/producer; Order chặn lần thêm giỏ kế tiếp + đánh dấu review.
- [ ] Quyết định D19 (OTP xác thực thiết bị).

## P7 — Assistant

- [ ] Knowledge base (chính sách hoàn, FAQ), API hỏi đáp qua Gateway, lấy ngữ cảnh Order/Product của chính user, test chống rò rỉ dữ liệu người khác.

## P8 — Dữ liệu cá nhân

- [ ] `legal_compliance.md`, UI consent, pipeline chỉ dùng dữ liệu hash, job xóa dữ liệu hành vi quá hạn.

## P9 — Hoàn thiện

- [ ] Rate limiting ở Gateway; network isolation cho cổng service.
- [ ] CI/CD: Testcontainers, build Docker image, `docker-compose.prod.yml`, deploy VPS.
- [ ] OpenAPI cho 8 service; README mục giới hạn đầy đủ.
