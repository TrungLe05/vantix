# E2E (Playwright)

Kiểm thử giao diện + chức năng như người dùng thật, vai trò ADMIN và BUYER, trên stack đang chạy: Gateway → user/product/order → PostgreSQL/Redis/Kafka, cùng frontend. Spec không tự dựng server; chưa chạy trong CI vì CI không có stack đầy đủ.

| Spec | Nội dung |
|---|---|
| `admin.spec.ts` | Đăng nhập ADMIN; wizard tạo event GA và event ghế ngồi (sơ đồ ghế, gán ghế, publish); publish thiếu điều kiện hiện lý do từ backend; trang venues/events; BUYER không vào được `/admin` |
| `buyer.spec.ts` | Khách xem event; giữ vé GA → sửa số lượng → confirm; giữ ghế, buyer khác thấy ghế bị giữ; hai buyer tranh một ghế (1 thắng); giới hạn 10 vé/Order; bỏ vé cuối → giỏ hủy; ADMIN hủy event → giỏ hủy **qua Kafka** (kiểm bằng `GET /orders/{id}`, endpoint không gọi lưới an toàn availability) |
| `account.spec.ts` | Sai mật khẩu; validate form đăng ký; giữ phiên khi reload (refresh cookie); đăng xuất; tài khoản bắt buộc đổi mật khẩu → `/change-password` |

## Biến môi trường

| Biến | Ý nghĩa |
|---|---|
| `E2E_BASE_URL` | URL frontend (mặc định `http://localhost:3000`) |
| `E2E_API_URL` | URL Gateway gồm `/api` (mặc định `http://localhost:8999/api`) — `buyer.spec.ts` dựng dữ liệu qua API bằng token ADMIN |
| `E2E_ADMIN_EMAIL`, `E2E_ADMIN_PASSWORD` | Tài khoản seed ADMIN (`DEFAULT_ADMIN_*` của user-service) |
| `E2E_BUYER_PASSWORD` | Mật khẩu chung `buyer0..4@gmail.com` (`DEFAULT_BUYER_PASSWORD`) |
| `E2E_MCP_EMAIL`, `E2E_MCP_PASSWORD` | Tùy chọn: tài khoản `must_change_password = true` (vd STAFF do ADMIN tạo, mật khẩu tạm nhận qua email). Thiếu thì test đổi mật khẩu bị skip |
| `E2E_BROWSER_CHANNEL` | `chrome`/`msedge` để dùng browser đã cài; bỏ trống thì cần `npx playwright install chromium` |

Không commit giá trị thật. Seed chỉ tạo tài khoản khi chưa tồn tại, nên dùng DB mới (vd `*_e2e` trong `docker/postgres/test/create-test-databases.sql`) nếu muốn tự đặt mật khẩu seed.

## Chạy

```bash
cd frontend
E2E_BASE_URL=http://localhost:3000 E2E_API_URL=http://localhost:8999/api \
E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... E2E_BUYER_PASSWORD=... E2E_BROWSER_CHANNEL=chrome \
npx playwright test
```

Chạy cạnh `npm run dev` đang mở: đặt `NEXT_DIST_DIR=.next-e2e` cho server frontend thứ hai (vd `npx next dev -p 3001`) để không dùng chung thư mục build. Báo cáo HTML: `npx playwright show-report`.

## Lưu ý dữ liệu

- Mỗi lần chạy tạo event mới (tên có hậu tố thời gian); buyer0..4 giữ vé trên các event đó tới khi hết hạn 10 phút.
- Hai buyer tranh ghế cần `buyer3`, `buyer4` chưa có giỏ trên event vừa tạo — luôn đúng vì event mới mỗi lần chạy.
