# Vantix Frontend — Phase 5: Notification & Check-in UI Plan

> **Trạng thái:** Đã triển khai 14/11/2026. **Không có thiết kế UXMagic cho P5 và phiên làm việc này không truy cập UXMagic MCP** — UI dùng lại component/token của trang order và drawer có sẵn trong `EventHeader`, chưa đối chiếu với thiết kế nào.
> **Liên quan:** P5_notification_api_design.md, ../payment/P4_payment_ui_plan.md.

## 1. Phạm vi

Không thêm dependency (D36). Thay đổi:

- `src/types/notifications.ts`, `src/lib/api/notifications.ts` — NT01–NT06; `openNotificationSocket` lấy ticket rồi mở WebSocket (URL suy ra từ `NEXT_PUBLIC_API_BASE_URL`, `http→ws`, `https→wss`).
- `src/types/orders.ts`, `src/lib/api/orders.ts` — `OrderTicket`, `CheckInResult`, `getTicketQr` (blob), `checkInTicket` (4017 trả về như kết quả, không ném lỗi).
- `src/components/events/EventHeader.tsx` — badge số chưa đọc, danh sách thông báo trong drawer, "Mark all as read", WebSocket realtime cho BUYER; nút "Check-in" cho ADMIN/STAFF.
- `src/app/(main)/orders/[orderId]/page.tsx` — mục "Your tickets": QR + mã nhập tay cho vé `VALID`, nhãn "Checked in · thời điểm" / "Cancelled".
- `src/app/(main)/check-in/page.tsx` (route mới) — chọn event, nhập/quét mã, kết quả.

## 2. Luồng

- **Buyer:** thanh toán xong → WebSocket đẩy "Your tickets are confirmed" → header làm mới badge, trang order làm mới (invalidate `["order", id]`) và hiện vé ngay. Mở drawer → bấm thông báo → đánh dấu đã đọc + đi tới `/orders/{id}`.
- **STAFF/ADMIN:** header → "Check-in" → chọn event (danh sách công khai, chỉ `PUBLISHED`) → quét camera (`BarcodeDetector`, Chrome/Edge/Android) hoặc nhập/dán mã (máy quét USB/Bluetooth hoạt động như bàn phím) → kết quả lớn, rõ:
  - xanh "Valid ticket — let them in";
  - đỏ "Already checked in — do not admit" + lần quét đầu (giờ, "you" hoặc staff id rút gọn);
  - đỏ "Ticket not accepted" + message backend (4016/4018).
  Quét tạm dừng khi đang hiện kết quả; "Scan next ticket" để tiếp tục.

## 3. Quy tắc

- Ẩn/hiện theo role chỉ là UX; OR13 và trang `/check-in` được backend chặn (BUYER → 1004 → `/403`).
- QR tải bằng `apiClient` (cần JWT), tạo object URL, thu hồi khi unmount (`gcTime: 0` để không dùng lại URL đã thu hồi).
- WebSocket rớt → nối lại với backoff `1s × 2^n` tối đa 30 giây, mỗi lần lấy ticket mới; badge còn refetch 60 giây làm lưới an toàn.
- Lỗi hiển thị bằng `getErrorMessage`.

## 4. Kiểm tra

- Đã chạy: `npx tsc --noEmit`, `npx eslint src e2e` (0 lỗi; 1 warning cũ ở `provider.tsx`), `npm run build`.
- `npm run lint` toàn thư mục báo lỗi trong `.open-next/` (output build Cloudflare, đã gitignore nhưng chưa nằm trong ignore của ESLint) — không liên quan P5.
- **Chưa** kiểm tra trên trình duyệt (camera, WebSocket qua Gateway), chưa có Playwright cho P5.
