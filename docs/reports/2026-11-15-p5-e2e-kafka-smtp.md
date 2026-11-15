# E2E P5: Kafka + SMTP + WebSocket (15/11/2026)

> Task P5 "Chạy end-to-end: Kafka thật, SMTP thật (hoặc mail catcher)" (`TASKS.md`). Code: nhánh `test/stripe-test-mode` (gồm #54, #55). Chạy ngay sau `2026-11-15-stripe-test-mode.md`, cùng stack.

## Môi trường

- Stack `*_e2e`: gateway, user, product, order, payment, **notification** (DB `notifications_service_e2e`), Kafka dev dùng chung. Mọi consumer group của stack test đọc từ `latest` (`SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET=latest`, group `notification-service-e2e-run`, `notification-service-account-e2e-run`) — tránh lặp lại phát hiện 2 của báo cáo Stripe.
- **SMTP: sink local** (script Python ~80 dòng, `127.0.0.1:2525`, không auth/TLS) thay cho mail catcher vì không được tạo container mới; Resend sandbox chỉ gửi được tới email chủ tài khoản. Sink chỉ in from/to/subject; nội dung lưu vào thư mục gitignored để script đọc OTP/mật khẩu tạm mà không in ra.
- WebSocket: client Python (`websockets`) lấy ticket `POST /api/notifications/ws-ticket` rồi mở `ws://localhost:8999/api/notifications/ws?ticket=…` **qua Gateway**, `Origin: http://localhost:3000`.
- Thanh toán: Stripe test mode thật (`stripe listen`, thẻ 4242 trên Stripe Checkout).

## Kết quả

| # | Luồng | Đường đi | Kết quả |
|---|---|---|---|
| 1 | Đăng ký → OTP | user `user.email-verification-requested` → Kafka → notification → SMTP | ✅ Email "Verify your Vantix account" ~0,5 giây sau đăng ký; OTP 6 số trong email → `verify-email` 200 → đăng nhập được |
| 2 | Quên mật khẩu → OTP | `user.forgot-password-requested` → SMTP | ✅ Email "Reset your Vantix password"; `reset-password` 200; mật khẩu cũ 401 (`2101`), mật khẩu mới 200 |
| 3 | ADMIN tạo STAFF → mật khẩu tạm | `POST /users/staff` → `user.staff-account-created` → SMTP | ✅ Email "Your Vantix staff account"; đăng nhập bằng mật khẩu tạm → `user.mustChangePassword = true`; gọi API ngoài allowlist → `9004` |
| 4 | Thanh toán → vé | Stripe webhook → `payment.succeeded` → Order `TICKETS_ISSUED` → `order.tickets-issued` → notification | ✅ WebSocket nhận frame `notification` ~1,6 giây sau webhook; `GET /notifications` có `TICKETS_ISSUED`, `unread-count = 1`; email "Your tickets are confirmed" (`email_status = SENT`, 1 lần) |
| 5 | Hoàn tiền | OR11 → `order.refund-requested` → notification; Stripe Refund → `refund.completed` → Order → `order.refunded` → notification | ✅ 2 frame WebSocket ("Refund in progress" lúc OR11, "Refund completed" ~1 giây sau webhook `refund.updated`); `unread-count = 3`; 2 email `SENT` |

Không có log lỗi ở notification-service; không log nội dung OTP/mật khẩu tạm.

## Phát hiện

1. **Stack dev sẽ nhận bản tin của lần chạy này** khi chủ repo bật lại: group `notification-service` / `notification-service-account` / `order-service` / `payment-service` của stack dev đã có offset nên sẽ đọc các bản tin phát trong lúc test. Hậu quả dự kiến: order-service dev log "Order không tồn tại"; notification-service dev tạo thông báo cho buyer e2e (không có trong `users_service`, tra contact thất bại → email lỗi/retry) và **thử gửi email mật khẩu tạm của STAFF e2e qua Resend** tới địa chỉ `@example.test` (Resend sandbox từ chối; email OTP đã quá 10 phút nên bị bỏ qua theo `isStale`). Không ảnh hưởng dữ liệu nghiệp vụ dev. Cách tránh triệt để: stack test dùng Kafka/topic riêng (TASKS). **Đã xử lý (15/11/2026, chủ repo đồng ý):** trước khi bật lại stack dev, kiểm tra các bản tin còn chờ của 4 group dev (`order-service`, `payment-service`, `notification-service`, `notification-service-account`) — tất cả đều có key là đơn/user e2e (user chỉ có trong `users_service_e2e`) — rồi `kafka-consumer-groups --reset-offsets --to-latest` đúng các topic đó (gồm `payment.failed`, `refund.failed` mà `order-service` dev chưa có offset); lag sau đó = 0.
2. `GET /notifications` không trả trạng thái email (`emailStatus`) — đúng thiết kế (chỉ nội bộ), ghi lại để khỏi nhầm khi đọc kết quả.
3. Checkout chuyển sang USD được bằng bàn phím (lần chạy Stripe trước để VND); xác nhận cả hai cách đều ghi nhận đúng USD.

## Chưa phủ

- Gửi qua **SMTP thật có auth/STARTTLS** (Resend): sink không có TLS/auth; cần chạy với `MAIL_*` thật và một địa chỉ nhận mà Resend sandbox cho phép.
- UI: chuông thông báo, trang vé/QR, camera check-in trên trình duyệt/điện thoại (task Playwright riêng).
- Nhiều instance notification-service (WebSocket session trong bộ nhớ — đã biết, P9).
