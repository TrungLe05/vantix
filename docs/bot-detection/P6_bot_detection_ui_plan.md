# Vantix Frontend — Phase 6: Bot Detection UI Plan

> **Trạng thái:** Đã triển khai 15/11/2026. **Không có thiết kế UXMagic cho P6 và phiên làm việc này không truy cập UXMagic MCP** — UI dùng lại component/token của trang admin venues/events, chưa đối chiếu với thiết kế nào.
> **Liên quan:** P6_bot_detection_api_design.md.

## 1. Phạm vi

Không thêm dependency. Thay đổi:

- `src/lib/device-id.ts` — `getDeviceId()`: UUID `crypto.randomUUID()` lưu `localStorage["vantix.deviceId"]`; localStorage bị chặn thì giữ trong bộ nhớ tới khi tải lại trang (D37).
- `src/lib/api/client.ts` — interceptor gửi `X-Device-Id` trên mọi request (Gateway cho phép mọi header qua CORS).
- `src/app/(main)/events/[eventId]/page.tsx` — gọi availability (OR07) khi mở trang: hiển thị "N left" cho vé GA và là tín hiệu "đã xem sự kiện" cho bot-detection (D38). Lỗi availability không chặn chọn vé.
- `src/types/bot-flags.ts`, `src/lib/api/bot-flags.ts` — OR14/OR15.
- `src/app/(admin)/admin/bot-flags/page.tsx` (route mới) + link "Bot review" trong menu admin.

## 2. Luồng

- **BUYER bị chặn:** OR01/OR04/OR06 trả `4019`; các màn hình thêm giỏ hiện có đã hiển thị `getErrorMessage` (message từ backend). Giỏ/hold hiện có vẫn xem, thanh toán, giảm số lượng được.
- **ADMIN:** Admin → Bot review → lọc Blocked/Cleared/All → bảng (buyer id rút gọn, thiết bị, điểm, 4 feature chính, số lần, lần cuối) → "Review" mở dialog đủ feature → "Clear flag" (OR15) hoặc "Keep blocked".

## 3. Quy tắc

- Ẩn/hiện theo role chỉ là UX; OR14/OR15 chặn ở backend (`@PreAuthorize("hasRole('ADMIN')")`).
- Không hiển thị deviceHash đầy đủ (8 ký tự đầu đủ để so khớp bằng mắt).
- Trang ghi rõ điểm đến từ model huấn luyện trên dữ liệu tự tạo — ADMIN cần xem tín hiệu trước khi quyết định.

## 4. Kiểm tra

- Đã chạy: `npx tsc --noEmit`, `npx eslint src` (0 lỗi; 1 warning cũ ở `provider.tsx`), `npm run build`.
- **Chưa** kiểm tra trên trình duyệt; chưa có Playwright cho P6.
