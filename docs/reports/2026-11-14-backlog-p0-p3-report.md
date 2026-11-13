# Báo cáo xử lý backlog P0–P3 và kiểm thử E2E (13–14/11/2026)

Người thực hiện: Claude (agent), theo quy trình tự động trong yêu cầu. Mọi PR vào `develop`, merge commit theo quy ước repo, chỉ merge khi toàn bộ check CI xanh. Chi tiết từng hạng mục: `TASKS.md`; quyết định: `DECISIONS.md` (D20–D26); nhật ký: `PROGRESS.md`.

## 1. Tóm tắt

- **9 PR** (#27–#34 đã merge, PR E2E đang review), ~**100 test backend** mới (unit + integration trên PostgreSQL/Redis thật) và **18 test E2E Playwright**. Trước đợt này repo chỉ có 2 test.
- **10 lỗi thật** được phát hiện bằng test và đã sửa, trong đó 4 lỗi nghiêm trọng về tính đúng đắn: race gán ghế (product), deadlock/500 khi expire song song (order), outbox product kẹt khi hủy event, consumer Kafka của order-service **chưa từng chạy**.
- Tính năng thêm: phân trang + lọc status cho `GET /orders`, giới hạn 10 vé/Order, retry/dead-letter/gauge cho outbox, OTP nguyên tử, tự tạo DB khi khởi tạo Postgres, trang bắt buộc đổi mật khẩu.
- Còn chặn (cần bạn): chạy k6 (máy chưa có `k6`), quyết định nghiệp vụ D24/D26, đánh giá rotate `JWT_SECRET_KEY`.

## 2. Các PR

| PR | Nội dung | Kết quả CI |
|---|---|---|
| [#27](https://github.com/TrungLe05/vantix/pull/27) | Init script tạo 5 DB trong Compose (`docker/postgres/init`) | ✅ merged |
| [#28](https://github.com/TrungLe05/vantix/pull/28) | Gateway: log mặc định INFO (DEBUG theo profile `dev`), test xác thực/allowlist `mcp`/preflight CORS | ✅ merged |
| [#29](https://github.com/TrungLe05/vantix/pull/29) | OTP nguyên tử (Lua + `SET NX`), test OTP + refresh token; CI có Postgres/Redis | ✅ merged |
| [#30](https://github.com/TrungLe05/vantix/pull/30) | Product: gán ghế nguyên tử, mã lỗi trùng ghế, validate field theo category, test | ✅ merged |
| [#31](https://github.com/TrungLe05/vantix/pull/31) | Order: sửa deadlock/optimistic lock khi expire song song, 39 test | ✅ merged |
| [#32](https://github.com/TrungLe05/vantix/pull/32) | Order: phân trang/lọc `GET /orders`, giới hạn 10 vé/Order; frontend đọc `PageResponse` | ✅ merged |
| [#33](https://github.com/TrungLe05/vantix/pull/33) | Outbox: backoff lũy thừa, dead-letter, gauge; sửa publisher product kẹt | ✅ merged |
| [#34](https://github.com/TrungLe05/vantix/pull/34) | k6 script vào repo, `invariants.sql`, khuyến nghị OR08 | ✅ merged |
| `test/e2e-playwright` | Playwright E2E; sửa consumer Kafka order-service và trang `/change-password` | xem PR |

## 3. Lỗi phát hiện và đã sửa

| # | Mức độ | Service | Lỗi | Phát hiện bằng | Sửa |
|---|---|---|---|---|---|
| 1 | 🔴 | order | `@KafkaListener` không được đăng ký: thiếu `spring-boot-starter-kafka` (Boot 4 tách auto-config). `product.event-cancelled` **chưa bao giờ được tiêu thụ**; hủy event chỉ có tác dụng khi ai đó gọi availability | E2E Playwright (consumer group không tồn tại trên Kafka) | Thêm starter; test `OrderEventListenerRegistrationTest` (fail khi bỏ starter) |
| 2 | 🔴 | product | Outbox đọc `cancelledAt` bằng `getTimestamp()`, không parse được `Instant.toString()` → mọi lần hủy event làm **cả outbox kẹt** | Integration test outbox | Parse trong `try` từng bản ghi; bản ghi hỏng chỉ retry/dead-letter |
| 3 | 🔴 | product | PR12 race: 8 request song song tạo hạng vé cùng ghế → 7 thành công, ghế bị ghi đè | Test song song | `UPDATE … WHERE ticket_type_id IS NULL`, lệch số dòng → 3205 + rollback |
| 4 | 🔴 | order | Expire song song (job + đọc + availability): HTTP 500 do `ObjectOptimisticLockingFailureException` và deadlock Hold↔Order | Test song song (lặp 6 lần) | Khóa Order → Hold theo id, job `SKIP LOCKED` (D23) |
| 5 | 🟠 | user | OTP đọc-rồi-ghi: đoán song song vượt giới hạn 5 lượt, mã đúng dùng được nhiều lần; cooldown gửi trùng | Test song song (code cũ fail 2 test) | Lua script + `SET NX` (D22) |
| 6 | 🟠 | frontend | Tài khoản `mustChangePassword` (STAFF do ADMIN tạo) bị chuyển tới `/change-password` nhưng trang không tồn tại → 404, không dùng được tài khoản | E2E Playwright | Thêm trang `(auth)/change-password` theo UI plan P1 |
| 7 | 🟠 | product | PR10 trùng ghế đã có trả 1005 thay vì 3203 | Integration test | `saveAllAndFlush` |
| 8 | 🟠 | product | PR12 không chặn gửi field sai category (API design yêu cầu 1001) | Integration test | Validate theo category; frontend không bị ảnh hưởng |
| 9 | ⚪ | k6 | Threshold chế độ GA luôn kỳ vọng 1 request thành công | Review | `min(VU, GA_AVAILABLE)` |
| 10 | ⚪ | repo | `k6/` bị `.gitignore` chặn cả thư mục → script không có trong repo | Kiểm tra trạng thái | Chỉ ignore `k6/.env*` |

## 4. Tính năng / thay đổi hành vi (cần lưu ý khi review)

- **`GET /api/orders`** (thay đổi contract): trả `PageResponse {content, page, size, totalElements, totalPages}`, query `status` (mặc định `CART,PENDING_PAYMENT`), `page`, `size` ≤ 100. Frontend đã cập nhật.
- **Giới hạn 10 vé/Order** (`ORDER_MAX_TICKETS_PER_ORDER`), lỗi mới `4012`. **Con số 10 là đề xuất (D24), cần bạn xác nhận.**
- **Outbox**: migration mới order `V2`, product `V4` (cột `last_error`, `dead_at`); sau 10 lần lỗi → dead-letter, log `OUTBOX_DEAD_LETTER`, gauge `outbox.pending/dead`. Producer Kafka `max.block.ms` 10s (trước 60s).
- **Compose**: tự tạo DB khi volume Postgres trống; volume cũ chạy tay script (README).
- **Gateway**: log mặc định `INFO`; muốn DEBUG đặt `SPRING_PROFILES_ACTIVE=dev`.
- **Order Kafka group** cấu hình được (`app.order.kafka-consumer-group`), mặc định giữ `order-service`.
- **Frontend**: trang `/change-password`; `next.config.ts` đọc `NEXT_DIST_DIR` (mặc định `.next`) để chạy server thứ hai; thêm devDependency `@playwright/test@1.65.0` (đã được bạn đồng ý).

## 5. Kiểm thử

**Backend** (`mvn --batch-mode --no-transfer-progress clean verify`, CI chạy cùng lệnh với service container Postgres + Redis):

| Module | Test | Nội dung chính |
|---|---|---|
| api-gateway | 12 | JWT 9001/9002/9003, refresh token không dùng làm access, xóa/inject `X-User-*`, allowlist `mcp`, preflight CORS |
| user-service | 15 | OTP giới hạn/dùng một lần/cooldown (cả song song), refresh rotation/reuse/song song |
| product-service | 18 | Publish gom lý do, ẩn DRAFT, PR10 all-or-nothing, PR12, gán ghế song song, outbox |
| order-service | 55 | Unit catalog, vòng đời/chuyển trạng thái, tranh ghế/GA, idempotency, expire đúng một lần, consumer hủy lặp, phân trang, giới hạn vé, outbox, đăng ký listener |

Integration test dùng DB riêng `*_test` (role `vantix_test`, script `docker/postgres/test/create-test-databases.sql`) và Redis DB 15 — không đụng dữ liệu dev (D21).

**E2E Playwright** (`frontend/e2e`, hướng dẫn trong `frontend/e2e/README.md`): chạy trên stack riêng của agent — Gateway/user/product/order build từ `develop` + thay đổi PR E2E, DB `*_e2e`, Redis DB 14, Kafka compose (group riêng), frontend cổng 3001, Chrome đã cài. Kết quả lần chạy cuối: **17 pass, 1 skip** (test đổi mật khẩu bắt buộc cần biến môi trường riêng; đã chạy pass riêng với tài khoản STAFF bật cờ `must_change_password` trên DB `users_service_e2e`).

| Vai trò | Luồng đã đi qua như người dùng |
|---|---|
| ADMIN | Đăng nhập → trang quản trị; wizard tạo event GA và event ghế ngồi (sơ đồ 2×3, gán ghế, publish); publish thiếu điều kiện hiện lý do; trang venues/events |
| BUYER | Xem event khi chưa đăng nhập; giữ 2 vé GA → tăng/giảm số lượng → xác nhận giỏ → thấy ở `/orders`; chọn ghế trên sơ đồ → giỏ; buyer khác thấy ghế "unavailable"; 2 buyer bấm cùng một ghế → 1 thành công, người kia nhận thông báo; chọn 11 vé → lỗi giới hạn; bỏ vé cuối → giỏ hủy; không vào được `/admin` |
| Hệ thống | ADMIN hủy event → outbox product → Kafka → order-service hủy giỏ (17ms sau khi publish; kiểm qua endpoint không có lưới an toàn) → UI hiện "Event cancelled", giỏ biến khỏi danh sách |
| Tài khoản | Sai mật khẩu, validate form đăng ký, giữ phiên khi reload (refresh cookie), đăng xuất, đổi mật khẩu bắt buộc |

**Frontend**: `npm run lint` 0 lỗi (1 warning có sẵn ở `provider.tsx`), `npx tsc --noEmit` sạch, `npm run build` thành công (có route `/change-password`).

## 6. Chưa làm được / cần bạn quyết định

| Hạng mục | Lý do | Cần gì |
|---|---|---|
| Kết quả k6 (P3-B) | Máy chưa cài `k6`; quy ước không pull image `grafana/k6`; seed chỉ có 5 BUYER | Cài k6 (vd `winget install k6`) → chạy theo `backend/services/order-service/k6/README.md` |
| E2E trên stack dev của bạn | Agent không đọc `.env` | Chạy `npx playwright test` với tài khoản thật (README) |
| Hết hạn 10 phút qua UI | Không chờ 10 phút trong E2E | Đã phủ ở integration test (giả lập deadline) |
| D24 — 10 vé/Order | Roadmap không quy định | Xác nhận con số |
| D26 — OR08 hủy giỏ | Quyết định nghiệp vụ | Đề xuất: bật cho CART, hoãn PENDING_PAYMENT tới P4 |
| Rotate `JWT_SECRET_KEY` | Fingerprint secret từng bị log ở bản cũ | Cho biết log dev cũ có từng chia sẻ/công khai không |
| Rule alert outbox | Chưa có Prometheus/Alertmanager | P9 |
| E2E trong CI | CI chưa dựng đủ stack | Có thể thêm job riêng sau |

## 7. Ghi chú môi trường

- Thay đổi port pgAdmin `8080 → 8081` trong `docker-compose.yml` của bạn **vẫn chưa commit** (không nằm trong PR nào).
- `.git/info/exclude` local của bạn loại thư mục `k6/`; file k6 trong repo đã được add tường minh.
- Tạo thêm trên Postgres compose: role `vantix_test`, DB `*_test` và `*_e2e` (chỉ phục vụ test).
- Stack E2E (cổng 18999/19001/19003/19004/3001) đã dừng sau khi test. Các service dev của bạn ở 3000/8999/900x **không còn chạy** tại thời điểm kết thúc; agent không dừng các PID đó (chỉ dừng 6 PID của stack E2E).
- Secret/mật khẩu test của stack E2E sinh ngẫu nhiên, nằm ngoài repo (scratchpad của phiên).
