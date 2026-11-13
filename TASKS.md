# Vantix — Danh sách công việc

> Cập nhật: 13/11/2026. Ưu tiên: 🔴 cao (chặn phase hiện tại) · 🟠 trung bình · ⚪ thấp. Đánh dấu `[x]` khi xong và cập nhật `PROGRESS.md`.

## Đang làm — hoàn tất P3 Order

- [ ] 🔴 Chạy PostgreSQL/Redis/Kafka end-to-end cho luồng tạo hold → thêm → sửa GA → confirm → hết hạn → hủy event.
- [ ] 🔴 Chạy k6 `backend/services/order-service/k6/seat-contention.js` (cùng seat và GA), lưu kết quả: cấu hình tải, commit, môi trường, invariant cuối (≤ 1 hold/ghế, GA không vượt tổng), expiry lag.
- [ ] 🔴 Test concurrency/integration cho order-service (Testcontainers PostgreSQL + Redis): tranh ghế, tranh GA, idempotency replay/conflict, expire nhả tồn đúng một lần, consumer hủy event lặp.
- [ ] 🟠 Unit test `CatalogValidationService`, `OrderCommandService` (state transition).
- [ ] 🟠 Giới hạn số vé tối đa mỗi request/Order.
- [ ] 🟠 Phân trang/lọc status cho `GET /orders`.
- [ ] 🟠 DLQ/alerting cho outbox (`order_event_outbox`, `event_outbox`), backoff thay vì cố định 5 giây.
- [ ] ⚪ Quyết định có dùng OR08 (hủy giỏ) ở frontend không.

## Nợ P0–P2

- [ ] 🔴 Bootstrap database trong Compose (init script tạo `users_service`, `products_service`, `orders_service`, `payments_service`, `notifications_service`).
- [ ] 🟠 Test product-service: publish validation (gom lý do), PR10 all-or-nothing, PR12 ràng buộc seat, ẩn DRAFT.
- [ ] 🟠 Test user-service: refresh rotation/reuse detection, OTP attempts/cooldown, mcp allowlist ở Gateway.
- [x] Gỡ log fingerprint JWT secret trong `JwtUtils`; log DataIntegrityViolation, token bị từ chối ở Gateway, lỗi OAuth2 chỉ còn loại lỗi/mã lỗi (13/11/2026).
- [x] Sửa `.env.example` theo biến code đang đọc, thêm `frontend/.env.example`, bỏ chặn `.env.example` trong `.gitignore` (13/11/2026).
- [ ] 🟠 Rà soát bí mật cũ đã từng bị log (fingerprint JWT secret): nếu log dev có thể đã chia sẻ, đổi `JWT_SECRET_KEY`.
- [ ] ⚪ Hạ `logging.level.com.dev.api_gateway` từ `DEBUG` về `INFO` khi deploy (P9, tách theo profile).
- [ ] ⚪ OTP nguyên tử bằng `INCR`/Lua (giới hạn đã biết của P1).
- [ ] ⚪ Kiểm chứng preflight `OPTIONS` qua Gateway.

## Tài liệu

- [x] Cập nhật `AGENTS.md`: tên DB, cấu trúc `backend/`, package, phase, golden example (13/11/2026).
- [x] Cập nhật `docs/auth/P1_auth_design.md`: ERD bỏ `USER_OTP`/`expires_at`, sequence OTP Redis, cookie, `oauth2Login` + exchange (13/11/2026).
- [ ] ⚪ Thay `frontend/README.md` (template create-next-app) bằng hướng dẫn thật.
- [ ] ⚪ Viết `01_system_architecture.md` hoặc trỏ hẳn sang `ARCHITECTURE.md` trong roadmap.

## P4 — Payment

- [ ] Thiết kế `P4_payment_design.md`, `P4_payment_api_design.md` (ERD `payments_service`, sequence thanh toán/hoàn, schema `payment.succeeded`/`payment.failed`/`refund.completed`/`refund.failed`).
- [ ] Chốt xử lý payment thành công đến sau deadline hold; đồng bộ deadline Order ↔ Payment.
- [ ] Stripe Checkout/PaymentIntent (test), Idempotency-Key khi khởi tạo thanh toán.
- [ ] Webhook xác thực chữ ký, idempotent theo Stripe event id.
- [ ] Order consumer: thành công → `PAID`, hold `PURCHASED`, held → sold; thất bại → nhả hold.
- [ ] Refund: Order tính % theo 3 mốc, Payment gọi Refund API; hủy event → hoàn 100%.
- [ ] Frontend: bước thanh toán sau `PENDING_PAYMENT`.

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
