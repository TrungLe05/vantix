# Vantix Frontend — Phase 4: Payment UI Plan

> **Trạng thái:** Đã triển khai 14/11/2026 trên các màn hình Order có sẵn. **Không có thiết kế UXMagic cho P4 và phiên làm việc này không truy cập được UXMagic MCP** — UI mới dùng lại component, token và bố cục của trang order detail P3, chưa đối chiếu với thiết kế nào.
> **Liên quan:** P4_payment_api_design.md, ../order/P3_order_ui_plan.md.

## 1. Phạm vi

Không thêm route, không thêm dependency (Stripe Checkout là trang chuyển hướng, không cần `@stripe/stripe-js`). Thay đổi ở:

- `src/types/orders.ts` — `OrderStatus` thêm `REFUND_PENDING`/`REFUNDED`; `Order.paidAt`, `Order.refund`; type `CheckoutSession`, `RefundQuote`.
- `src/lib/api/orders.ts` — `startCheckout` (OR09), `getRefundQuote` (OR10), `requestRefund` (OR11), `getPurchasedOrders` (OR02 lọc `PAID,TICKETS_ISSUED,REFUND_PENDING,REFUNDED`).
- `src/app/(main)/orders/[orderId]/page.tsx` — thanh toán và hoàn tiền.
- `src/app/(main)/orders/page.tsx` — mục "Purchases" dưới danh sách giỏ.

## 2. Luồng buyer

1. Giỏ `CART` → "Confirm cart" (như P3) → `PENDING_PAYMENT`.
2. `PENDING_PAYMENT`: đồng hồ đếm ngược vẫn hiển thị (deadline hold cũng là hạn thanh toán), nút **"Pay $X"** gọi OR09 rồi `window.location.assign(checkoutUrl)`.
3. Stripe trả về `/orders/{id}?payment=success`: hiển thị "Confirming your payment", refetch OR03 mỗi 2 giây tới khi rời `PENDING_PAYMENT`. `?payment=cancelled`: báo "Payment was not completed", vẫn cho trả lại nếu còn hạn.
4. `PAID`: banner "Payment successful", nút **"Cancel order and refund"** mở hộp thoại: nêu chính sách 3 mốc, gọi OR10 hiển thị `%` và số tiền; mốc 0% thì nút xác nhận bị khóa kèm lý do. Xác nhận gọi OR11.
5. `REFUND_PENDING`/`REFUNDED` (và refund `LATE_PAYMENT` trên Order `EXPIRED`/`CANCELLED`): banner trạng thái hoàn tiền theo `refund.status` + `refund.reason`; refetch 5 giây khi đang chờ.

## 3. Quy tắc

- Không giả lập thanh toán; `?payment=success` chỉ đổi thông báo và nhịp refetch, trạng thái lấy từ backend.
- Số tiền hoàn hiển thị là ước tính — backend tính lại khi xác nhận (ghi rõ trong hộp thoại).
- Lỗi hiển thị bằng `getErrorMessage` (message từ backend), không tự dịch errorCode.
- Nút "Cancel cart" vẫn chỉ cho `CART`; backend nay cũng từ chối `PENDING_PAYMENT` (4005).
- Đơn đang/đã hoàn hiển thị các item `CANCELLED` (vé đã trả về kho) để buyer vẫn thấy mình đã mua gì.

## 4. Kiểm tra

- Đã chạy: `npx tsc --noEmit`, `npm run lint` (0 lỗi), `npm run build`.
- **Chưa** kiểm tra hiển thị/tương tác trên trình duyệt và chưa có Playwright cho luồng thanh toán (cần stack đầy đủ + Stripe test mode).
