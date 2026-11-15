# Vantix — Tiến độ

> Cập nhật: 15/11/2026 · `develop` có P4, P5, P6 (#53); PR #54 (`feature/refund-checkin-admin-ux`, sửa sau chạy thử) và #55 (`fix/bot-false-positives`, D46) đang chờ merge. Trạng thái lấy từ code, không chỉ từ docs.

## Tổng quan

| Phase | Nội dung | Trạng thái | Ghi chú |
|---|---|---|---|
| P0 | Hạ tầng, khung 8 service, CI | ✅ Xong | Compose tự tạo 5 DB khi volume trống (init script); `docker-compose.prod.yml`, `Dockerfile` gốc rỗng |
| P1 | User & Auth | ✅ Xong | 14 API (gồm tạo STAFF), API nội bộ contact/account (P5, P6); 19 integration test (OTP, refresh rotation) |
| P2 | Product (catalog) | ✅ Xong | 14 API (PR01–PR14), venue có tọa độ (D45); 20 integration test; Compose tự tạo DB |
| P3 | Order: giỏ, hold, chống bán trùng | ✅ Xong | 9 API + UI (có hủy giỏ CART); integration/concurrency test, E2E Playwright, k6 (đúng đắn đạt, thông lượng/event còn thấp) |
| P4 | Payment (Stripe) | 🟡 Đã code + integration test + chạy Stripe test mode | Stripe Checkout, webhook, hoàn tiền, UI; đã chạy thật thanh toán/hết hạn/hoàn tiền với Stripe sandbox (15/11/2026); chưa có E2E Playwright cho UI |
| P5 | Notification & check-in | 🟡 Đã code + integration test | Vé/QR, check-in chống quét trùng, thông báo + email + WebSocket, email OTP/STAFF; chưa chạy E2E với Kafka/SMTP thật |
| P6 | Bot-detection | 🟡 Đã code + huấn luyện | Device ID, feature Redis, Isolation Forest trên dữ liệu k6 tự tạo (test offline: precision 0.985, recall 0.933, chặn nhầm 3.5% tài khoản người dùng; live vòng C chấm lại sạch: ~1% người dùng bị flag — con số 8% ban đầu do lỗi dữ liệu k6), chính sách D46 (chặn khi HIGH kèm luật hoặc lặp lại, HIGH đơn lẻ → WATCH: test B chặn nhầm 0/200), chặn 4019, trang review ADMIN; chưa E2E trình duyệt |
| P7 | AI Assistant | ⬜ Chưa bắt đầu | Chỉ `/health` |
| P8 | Dữ liệu cá nhân | ⬜ Chưa bắt đầu | |
| P9 | Hoàn thiện, deploy | ⬜ Chưa bắt đầu | |

## Chi tiết đã có trong code

### P1 — User & Auth
- `user-service`: register, verify-email, resend-verification, login, Google OAuth2 (`oauth2Login`) + exchange, refresh (rotation + reuse detection), logout, forgot/reset/change password, `GET /users/me`, `POST /users/staff` (ADMIN).
- OTP ở Redis (HMAC), refresh token JWT trong cookie httpOnly, claim `mcp` ép đổi mật khẩu.
- Seed: 1 ADMIN, 1 STAFF, 5 BUYER (`buyer0..4`).
- Gateway: `JwtAuthenticationGlobalFilter`, public-endpoints, password-change allowlist, CORS.
- Kafka producer 3 topic `user.*` (notification-service consume từ P5).
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
- k6 `backend/services/order-service/k6/seat-contention.js`, kết quả `k6/results/2026-11-14-contention.md`.
- Test: unit + integration/concurrency (Postgres/Redis thật), E2E Playwright `frontend/e2e`.
- Frontend: Continue tạo hold từ event detail/seat map, trang orders (list) và order detail (countdown, sửa GA, bỏ item, confirm), không giả lập payment.

### P4 — Payment
- payment-service: `POST /internal/payments/checkout-sessions` (nội bộ, Order gọi), `POST /payments/webhooks/stripe` (công khai, xác thực chữ ký), consumer `order.refund-requested`, outbox `payment_event_outbox`, scheduler expire phiên theo deadline hold và retry refund. Stripe sau interface `StripeGateway` (stripe-java 34.1.0).
- order-service: OR09 checkout, OR10 refund-quote, OR11 refund; consumer `payment.succeeded`/`payment.failed`/`refund.completed`/`refund.failed`; trạng thái `REFUND_PENDING`/`REFUNDED`, bảng `order_refunds` (migration V3); OR08 chỉ còn cho CART; hủy event hoàn 100% đơn đã thanh toán.
- Gateway: webhook Stripe vào `public-endpoints`.
- Frontend: nút "Pay" (chuyển hướng Stripe Checkout), chờ xác nhận sau khi quay về, hộp thoại hoàn tiền có xem trước số tiền, mục Purchases ở trang orders.
- Test: payment 19 case, order thêm 20 case (15 thanh toán/hoàn, 4 chính sách, 1 outbox); tổng order 75.
- Quyết định mới D27–D30 (`DECISIONS.md`).

### P5 — Notification & check-in
- order-service: bảng `tickets` (migration V4), phát hành vé cùng transaction `payment.succeeded` (→ `TICKETS_ISSUED`), OR12 ảnh QR (zxing), OR13 check-in ADMIN/STAFF (update nguyên tử, quét trùng 4017 + ai/lúc nào), hoàn tiền làm vé `VOID`, chặn hoàn đơn đã có vé check-in; outbox `order.tickets-issued`, `order.refunded`.
- notification-service (trước là khung rỗng): consumer `order.tickets-issued`/`order.refund-requested`/`order.refunded` → bảng `notifications` (idempotent theo `dedup_key`) + đẩy WebSocket + email qua scheduler có retry; consumer `user.*` gửi email OTP/mật khẩu tạm (không lưu, không log); API NT01–NT05; WebSocket `/api/notifications/ws` với ticket một lần.
- user-service: `GET /internal/users/{id}/contact` (role INTERNAL); bật `@EnableMethodSecurity`.
- Gateway: `GET /api/notifications/ws` vào `public-endpoints`.
- Frontend: mục "Your tickets" (QR + mã), chuông thông báo realtime có badge, trang `/check-in` (camera `BarcodeDetector` hoặc nhập mã).
- Test: order 84 (thêm 9 vé/check-in), notification 14, user 18 (thêm 3).
- Quyết định mới D31–D36 (`DECISIONS.md`).

### P6 — Bot-detection
- order-service: filter `X-Device-Id` → HMAC (`DEVICE_HASH_SECRET`), availability ghi lần xem đầu (Redis), `cart.item_added` thêm `deviceHash`/`eventViewedAt` (payload JSON của outbox); consumer `bot_score.evaluated` → bảng `bot_flags` (migration V5); OR01/OR04/OR06 chặn 4019 theo buyer/thiết bị, hold cũ giữ nguyên; OR14/OR15 ADMIN xem/gỡ.
- bot-detection-service (trước là khung `/health`): feature theo cửa sổ Redis, tuổi tài khoản từ user-service, Isolation Forest (`model/`), consumer/producer aiokafka at-least-once, BD01–BD03; chế độ `UNSCORED` khi chưa có model.
- user-service: `GET /internal/users/{id}/account` (role INTERNAL, chỉ `createdAt`).
- Dữ liệu và model: `datagen/` (k6 + seed trên stack `*_e2e`), `training/` (dataset, train, notebook), báo cáo `docs/bot-detection/P6_bot_detection_report.md`.
- Frontend: header `X-Device-Id`, trang chi tiết gọi availability ("N left"), trang admin "Bot review".
- CI: job `bot-detection` (pytest, Python 3.12, Redis).
- Test: order 91 (thêm 7), user 19 (thêm 1), bot-detection 17 (pytest).
- Quyết định mới D37–D42 (`DECISIONS.md`).

### Sửa sau chạy thử (15/11/2026)
- order-service: hoàn vé chưa dùng khi đơn có vé đã check-in (D43) — chỉ nhả kho phần chưa dùng; OR10 thêm `reason`, `refundableTickets`, `usedTickets`; check-in chỉ trong khung 3 giờ trước → kết thúc (D44, lỗi 4021); `ProductCatalog` đọc thêm `endAt`.
- product-service: venue có `latitude`/`longitude` (migration V5, bắt buộc khi tạo, D45); PR13 sửa `totalQuantity` của GA khi event còn DRAFT (lỗi 3304).
- Frontend: check-in dạng card + quét liên tục; dialog hoàn tiền báo đúng lý do; nhắc đăng nhập + quay lại sau khi đăng nhập; bảng sự kiện sắp theo nhóm; wizard sửa hạng vé tại chỗ; trang tạo STAFF; bản đồ Leaflet + Nominatim cho venue.
- Test: order 95 (thêm 4), product 20 (thêm 2). Chưa kiểm tra UI trên trình duyệt.

### Vòng làm task tự động (15/11/2026)

- **BLOCKED — Chạy thật Stripe test mode** (và kéo theo **E2E Kafka/SMTP**, vì email "đặt vé" cần thanh toán Stripe). Đã xác nhận: Stripe CLI đăng nhập được, secret của `stripe listen` khớp `STRIPE_WEBHOOK_SECRET`, key là `sk_test_`. Vướng: cả stack dev của chủ repo đang chạy trên cổng 3000/8999/9000–9004 nên stack riêng (DB `*_e2e`) không khởi động được; dùng stack dev thì phải tạo tài khoản/đơn test trong DB dev đang dùng, webhook đi vào payment-service dev, Kafka dùng chung. **Câu hỏi:** chạy thử trên stack dev đang chạy (tạo một buyer test trong `users_service`, đơn test trong DB dev) được không, hay tắt stack dev để chạy stack riêng trên DB `*_e2e`? **Đã trả lời (15/11/2026):** chủ repo tắt stack dev, chạy stack riêng trên DB `*_e2e` — task chờ chạy, chưa có kết quả.
- **DONE — Giảm chặn nhầm bot-detection** (D46): đọc lại `bot_score.evaluated` vòng C từ Kafka và tái hiện offline → 11/12 feature khớp, riêng `device_accounts_24h` lệch do UUID thiết bị "gia đình"/"farm" của k6 trùng giữa lần chạy thử và lần chạy live (Redis giữ 24 giờ) → chặn nhầm thật ~1% chứ không phải 8%; "botfarm 4/24" là 20 tài khoản bị chặn theo thiết bị trước khi có bản tin. Sửa `datagen/traffic.js` (mã riêng mỗi lần chạy). Chính sách mới: HIGH kèm luật tay (`ruleMatched`) → chặn; HIGH đơn lẻ → `WATCH`, HIGH lần hai → chặn (order migration V6, bot-detection thêm `ruleMatched`, trang Bot review có bộ lọc Watching). Test B: người dùng bị chặn 7/200 → 0/200, bot 49/56 → 47/56. Script `training/evaluate_policy.py`. Test: order 97, pytest 18.
- **DONE — Chạy thật Stripe test mode** (15/11/2026, stack `*_e2e`, Stripe CLI `listen`): hết hạn (scheduler expire phiên đúng deadline hold → Order `EXPIRED`), thanh toán thẻ 4242 (webhook `checkout.session.completed` → 80.00 USD `SUCCEEDED` → `TICKETS_ISSUED`, 2 vé), hoàn tiền OR11 (100% → Stripe Refund → `refund.updated` → `REFUNDED`, vé `VOID`, nhả kho) đều đạt. Phát hiện: Checkout hiển thị VND do Adaptive Pricing của tài khoản sandbox (ghi nhận vẫn đúng USD); consumer group test mới đọc lại bản tin dev từ offset 0 (không hỏng dữ liệu). Chi tiết `docs/reports/2026-11-15-stripe-test-mode.md`.
- **Bước tiếp theo:** E2E Kafka/SMTP (P5) trên cùng stack `*_e2e` + notification-service; còn lại theo thứ tự ưu tiên 🟠 trong `TASKS.md` (Playwright/kiểm tra trình duyệt, ESLint bỏ qua `.open-next`, …).

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

- 181 test tự động backend + 18 pytest bot-detection (unit + integration trên Postgres/Redis thật, CI dùng service container); E2E Playwright cho P1–P3, chưa có cho thanh toán/vé/bot review.
- P4 đã chạy thật với Stripe test mode (thanh toán, hết hạn, hoàn 100%); hoàn 50%/0%, `refund.failed` thật và thanh toán async chưa chạy với Stripe thật.
- Refund `FAILED` phải xử lý tay (log `REFUND_FAILED`); chưa có API quản trị.
- P5 chưa chạy end-to-end (Kafka + SMTP thật, WebSocket qua Gateway, camera trên thiết bị thật). Dev không có SMTP thì email OTP bị bỏ qua (`EMAIL_SKIPPED`), cần cấu hình `MAIL_HOST` để đăng ký tài khoản mới.
- Phiên WebSocket và ticket nằm trong bộ nhớ notification-service: chỉ đúng với một instance (P9).
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
