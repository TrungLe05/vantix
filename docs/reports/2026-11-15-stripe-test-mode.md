# Chạy thật Stripe test mode (15/11/2026)

> Task P4 "Chạy thật với Stripe test mode" (`TASKS.md`). Code: nhánh `fix/bot-false-positives` (PR #55, gồm #54). Không dùng UI Vantix: luồng buyer gọi API qua Gateway, trang thanh toán là Stripe Checkout thật (sandbox).

## Môi trường

- Stack riêng: gateway, user, product, order, payment (jar build 15/11) trên DB `*_e2e` (Postgres compose), Redis DB 3, Kafka dev dùng chung với consumer group riêng (`order-service-stripe-run`, `payment-service-stripe-run`). Stack dev của chủ repo đã tắt.
- `stripe listen --events checkout.session.completed,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,checkout.session.expired,refund.updated,refund.failed --forward-to localhost:8999/api/payments/webhooks/stripe` (Stripe CLI 1.55.11, khóa `sk_test_`).
- Buyer: tài khoản `dg-c-human-*` của datagen vòng C; sự kiện "Datagen C show 1" (bắt đầu 24/12/2026), vé GA 40 USD × 2 = 80 USD.

## Kết quả

| Luồng | Bước | Kết quả |
|---|---|---|
| Hết hạn | OR01 → OR06 → OR09, không thanh toán | ✅ Deadline hold 09:34:39Z; 09:34:40Z scheduler Payment expire phiên Stripe → `payments.status = EXPIRED`, outbox `payment.failed`; webhook `checkout.session.expired` nhận 09:34:41Z, không log lỗi; Order `EXPIRED`, hold `EXPIRED`, 0 vé |
| Thanh toán | OR01 → OR06 → OR09 → Stripe Checkout, thẻ `4242 4242 4242 4242` | ✅ Stripe chuyển về `localhost:3000/orders/{id}?payment=success`; webhook `checkout.session.completed` → `payments` `SUCCEEDED` 80.00 USD → `payment.succeeded` → Order `TICKETS_ISSUED`, 2 vé |
| Hoàn tiền | OR10 → OR11 | ✅ OR10: 100%, 80 USD, `refundableTickets = 2`; OR11 → `REFUND_PENDING`, vé `VOID`, hold `RELEASED` (nhả kho) → `order.refund-requested` → Payment gọi Stripe Refund → webhook `refund.updated` → `refunds` `SUCCEEDED` 80.00 USD → `refund.completed` → Order `REFUNDED`, `order_refunds` `COMPLETED` (~3 giây từ OR11) |

## Phát hiện

1. **Stripe hiển thị VND** (Adaptive Pricing của tài khoản sandbox): trang Checkout mặc định chọn 2.140.226 ₫, có nút đổi sang 80,00 US$. Buyer trả bằng VND nhưng phiên vẫn ghi nhận 80.00 USD và Payment so khớp đúng — không lỗi. Nếu muốn buyer chỉ thấy USD: tắt Adaptive Pricing trong Stripe Dashboard (cấu hình tài khoản, không phải code).
2. **Consumer group mới đọc từ offset 0**: group `*-stripe-run` chưa có offset nên đọc lại toàn bộ topic trên Kafka dev. Payment e2e nhận `order.refund-requested` cũ của đơn dev `762245b0…` → không thấy payment trong DB e2e → phát `refund.failed`; order e2e log `payment.succeeded cho Order không tồn tại` cho các đơn dev. Không hỏng dữ liệu: đơn dev đó đã `REFUNDED` nên `handleRefundFailed` bỏ qua (refund không còn `REQUESTED`); order-service dev sẽ chỉ log lỗi "không tồn tại" cho `payment.failed`/`payment.succeeded` của đơn e2e. Đề xuất (TASKS): stack test đặt `spring.kafka.consumer.auto-offset-reset=latest` hoặc dùng topic/cluster riêng.
3. Trang thành công `?payment=success` cần frontend chạy ở cổng 3000 (lần này là Next dev của chủ repo, không đăng nhập) — UI sau thanh toán chưa được kiểm tra (task Playwright riêng).

## Chưa phủ

- `checkout.session.async_payment_*` (phương thức thanh toán chậm), `refund.failed` thật từ Stripe, hoàn 50%/0%, hủy sự kiện hoàn 100% — đã có integration test với Stripe giả lập, chưa chạy với Stripe thật.
- UI thanh toán/hoàn tiền trên trình duyệt, email/thông báo (P5 E2E).
