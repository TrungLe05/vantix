# Vantix — Quyết định kỹ thuật

> Nhật ký quyết định dạng ADR rút gọn. Quyết định đã chốt chỉ bàn lại khi có lý do mới phát sinh từ code thật. Nguồn: `docs/00_roadmap_phases_v1.md`, design/API design từng phase và code hiện tại.

| # | Quyết định | Trạng thái |
|---|---|---|
| D01 | Microservice theo quyền sở hữu dữ liệu, 8 service, monorepo | Chốt |
| D02 | Database-per-service; Gateway và assistant stateless | Chốt |
| D03 | Trust-The-Gateway: chỉ Gateway verify JWT | Chốt |
| D04 | Spring Boot 4.1 / Spring Cloud 2025.1 | Chốt |
| D05 | Common-lib theo rule of 3, tách artifact nhỏ | Chốt |
| D06 | Sync chỉ khi cần phản hồi ngay; còn lại Kafka | Chốt |
| D07 | Payment không biết vé; Order sở hữu vé/hoàn | Chốt |
| D08 | Chống bán trùng bằng đủ 3 lớp | Chốt |
| D09 | Quy tắc giỏ/hold P3 | Chốt |
| D10 | Order sở hữu tồn kho giao dịch, availability đọc từ Order | Chốt |
| D11 | Transactional outbox cho Product/Order | Chốt (P3) |
| D12 | Refresh token JWT trong cookie httpOnly, rotation + reuse detection | Chốt (P1) |
| D13 | OTP ở Redis, hash HMAC | Chốt (P1) |
| D14 | Google OAuth2 qua Spring Security + one-time code | Chốt (P1) |
| D15 | Bot-detection không giám sát, dữ liệu tự tạo; rủi ro cao không hủy hold | Chốt |
| D16 | Chính sách hoàn vé 3 mốc | Chốt |
| D17 | Format response/lỗi thống nhất | Chốt |
| D18 | Stripe test, USD | Chốt |
| D19 | Device verification OTP | **Mở** — quyết sau P6 |
| D20 | Bootstrap DB bằng init script của image postgres | Chốt |
| D21 | Integration test dùng Postgres/Redis thật: compose local, service container ở CI | Chốt |
| D22 | OTP nguyên tử: Lua script cho verify, `SET NX` cho cooldown | Chốt |
| D23 | Order: thứ tự khóa Order → Hold, khóa theo id, job expire `SKIP LOCKED` | Chốt |
| D24 | Tối đa 10 vé mỗi Order (cấu hình được) | Chốt tạm — chờ chủ sản phẩm xác nhận con số |
| D25 | Outbox: backoff lũy thừa, dead-letter sau 10 lần, gauge + log ERROR làm tín hiệu alert | Chốt |

---

### D01 — Tách service theo quyền sở hữu dữ liệu
Tiêu chí tách: ai sở hữu dữ liệu, tốc độ thay đổi, có phải hỏi ngược service khác không. Product đọc nhiều/ghi ít; Order ghi nhiều, nhiều trạng thái; bot-detection khác stack (Python). Monorepo để chia sẻ common-lib và CI.

### D02 — Database-per-service
Mỗi service có state có một DB PostgreSQL riêng, không join chéo. Hệ quả: eventual consistency giữa Product và Order (hủy event qua Kafka), Order phải gọi Product để xác minh catalog và fail closed khi không gọi được (4010).

### D03 — Trust-The-Gateway
Gateway luôn xóa `X-User-Id`/`X-User-Role` từ client, verify JWT rồi inject lại; service dùng `GatewayHeaderAuthFilter`. Chỉ chấp nhận `token_type=access`. Hệ quả: cổng service không được expose ra ngoài (siết bằng docker network ở P9). Phân quyền bằng `@PreAuthorize` ở method thay vì rule URL, vì 403 từ filter chain không đi qua `GlobalExceptionHandler`.

### D04 — Spring Boot 4.1
Roadmap v1 ghi Boot 3.x; code dùng 4.1 (Jackson 3 `tools.jackson`, `JacksonJsonSerializer`, gateway `server.webflux`, `spring-boot-starter-flyway`). Không viết API theo trí nhớ của 3.x.

### D05 — Common-lib theo rule of 3
Chỉ tách khi ≥ 3 service cần; mỗi artifact riêng dependency (`api-response`, `security`, `kafka`, `jwt`) để Gateway WebFlux không kéo theo `commonlib-security` (servlet).

### D06 — Sync vs async
- Order → Payment: REST sync (chỉ lấy session/`client_secret`).
- Stripe → Payment: webhook, bắt buộc idempotent.
- Payment → Order, Order → bot-detection, Order → Notification: Kafka.
- Order → Product (xác minh catalog): OpenFeign sync, timeout 2s/3s.
Lý do: sync giữ thread trong lúc chờ sự kiện không kiểm soát được thời điểm, dễ cascading failure.

### D07 — Payment chỉ biết tiền
Order sở hữu đơn, hold, vé/QR, check-in và tính % hoàn; Payment chỉ thực thi thanh toán/hoàn qua Stripe. Nếu Payment cần biết thêm về vé, đó là dấu hiệu sai ranh giới.

### D08 — Ba lớp chống bán trùng
1. Idempotency key: chống retry cùng client (không giải quyết hai người tranh một ghế).
2. Redis TTL: lớp nhanh, không tuyệt đối.
3. Ràng buộc/update nguyên tử ở DB Order: lớp quyết định cuối (`uq_holds_active_seat`, CHECK `held+sold<=total`, update có điều kiện).
Ba lớp giải quyết ba vấn đề khác nhau, không thay thế nhau.

### D09 — Quy tắc giỏ/hold (P3)
Một Order = một event; tối đa một CART mỗi buyer/event (partial unique index); hold chung 10 phút tính từ hold đầu, thêm item không gia hạn; tạo hold all-or-nothing; GA cùng ticket type gộp vào một dòng; confirm chuyển `CART → PENDING_PAYMENT`, chưa gọi Payment ở P3. Idempotency-Key bắt buộc cho tạo/thêm/confirm; PATCH quantity GA không cần vì đặt giá trị đích.

### D10 — Order sở hữu tồn kho giao dịch
Product chỉ giữ catalog tĩnh (ghế `ACTIVE/INACTIVE`, `totalQuantity`), không lưu HELD/SOLD. Order khởi tạo `ticket_inventory` GA từ Product lần đầu và fail closed nếu số lượng gốc đổi sau đó. UI ghép seat map Product + availability Order (public, refetch 15s); availability không phải reservation.

### D11 — Transactional outbox
P1 chấp nhận "gửi sau commit, lỗi chỉ log" (mất event thì người dùng tự gửi lại OTP). Từ P3, `product.event-cancelled` và `cart.item_added` mất là sai nghiệp vụ, nên ghi vào bảng outbox cùng transaction và scheduler gửi + retry. Hệ quả: at-least-once → consumer phải idempotent. Chưa có DLQ.

### D12 — Refresh token
Refresh token là JWT (`token_type=refresh`), DB chỉ lưu SHA-256 + `family_id` + `revoked_at`; rotation mỗi lần refresh, phát hiện dùng lại thì thu hồi cả family (ghi commit trước khi ném lỗi → dùng `TransactionTemplate`). Cookie `httpOnly; Path=/api/auth`; access token chỉ trong bộ nhớ frontend. Frontend phải serialize refresh.

### D13 — OTP ở Redis
Bỏ bảng `USER_OTP`: OTP là dữ liệu tạm có TTL. Hash `HMAC-SHA256(secret, userId:purpose:otp)` vì không gian OTP chỉ 10⁶. Gộp lỗi hết hạn/không tồn tại thành 2301. Chưa nguyên tử dưới tải cao (chấp nhận ở P1).

### D14 — Google OAuth2
Dùng `oauth2Login()` của Spring Security; callback 302 về frontend kèm one-time code (Redis 60s, `GETDEL`), frontend đổi code lấy token ở `/auth/oauth2/exchange`. Token không nằm trên URL; hệ thống phát hành JWT riêng, không dùng token Google. Cờ `isNewUser` cho onboarding.

### D15 — Bot-detection
Không có dataset công khai khớp hành vi mua vé → Isolation Forest trên dữ liệu tự tạo (k6 bot + thao tác thật). Kết quả rủi ro cao: chặn lần thêm giỏ kế tiếp + đánh dấu review, **không hủy hold hiện tại** (tránh phạt oan). Feature chỉ dùng hash device fingerprint, không IP thô/tên/email.

### D16 — Hoàn vé
≥ 7 ngày: 100%; 24h–7 ngày: 50%; < 24h: 0% (có thể chuyển nhượng sau); ADMIN hủy event: 100%. Order tính, Payment thực thi.

### D17 — Response và lỗi
`ApiResponse<T>{code=200, message?, result?}`; HTTP 201 vẫn `code: 200`. Lỗi: enum `ErrorCode` + `AppException` + `GlobalExceptionHandler`. Dải: 1xxx common, 2xxx user, 3xxx product, 4xxx order, 5xxx payment, 6xxx notification, 7xxx bot, 8xxx assistant, 9xxx gateway. Chống enumeration: không tồn tại và không có quyền trả cùng lỗi (3101 cho event DRAFT, 4001 cho Order người khác).

### D18 — Stripe test, USD
Stripe sandbox không hỗ trợ Việt Nam → tài khoản US, giá niêm yết USD.

### D19 — (Mở) OTP xác thực thiết bị mới
Phụ thuộc việc P6 có cần tín hiệu "thiết bị tin cậy" hay không. Không chặn tiến độ.

### D20 — Bootstrap DB bằng init script
`docker/postgres/init/01-create-databases.sql` mount vào `/docker-entrypoint-initdb.d`. Cân nhắc: (a) init script — đơn giản, không thêm container; (b) service one-shot chạy `psql` mỗi lần `up` — chạy cả với volume cũ nhưng thêm container; (c) để mỗi service tự tạo DB — Flyway không tạo được database. Chọn (a). Giới hạn: image chỉ chạy init khi volume trống, nên volume cũ phải chạy tay script (idempotent nhờ `\gexec` + `NOT EXISTS`, lệnh trong README).

### D21 — Môi trường integration test
Testcontainers là lựa chọn mặc định, nhưng môi trường dev hiện tại không được pull image hay tạo container ngoài project compose `vantix`. Vì vậy integration test kết nối Postgres/Redis qua biến môi trường: local dùng container compose đang chạy (DB/role test riêng, không đụng DB dev), CI dùng `services:` của GitHub Actions cùng image. Trade-off: test phụ thuộc hạ tầng đang chạy (không tự dựng), bù lại cùng một đường cấu hình cho local và CI. Kafka không cần cho các test này (publisher/consumer gọi trực tiếp).

### D22 — OTP nguyên tử
Trước đây `verify()` đọc JSON, so khớp, rồi ghi lại `attemptCount` ở Java; cooldown là `hasKey` rồi mới `SET`. Integration test song song chứng minh: 20 lần đoán sai đồng thời chỉ đếm được < 5 (vượt giới hạn), 10 lần gửi mã đúng đồng thời có > 1 lần qua. Cân nhắc: (a) `INCR` key đếm riêng — phải đổi định dạng lưu và vẫn cần bước "đúng thì xóa" nguyên tử; (b) `WATCH/MULTI` — retry phức tạp; (c) Lua script giữ nguyên định dạng `OtpEntry` JSON — chọn (c). Cooldown chuyển sang `SET NX EX 60` (`tryStartCooldown`). Giới hạn: so sánh hash trong Lua không constant-time (chấp nhận vì là HMAC phía server). Đọc thêm: `docs/auth/P1_auth_api_design.md` A02/A03/A10.

### D23 — Thứ tự khóa khi expire/hủy Order
Integration test song song (job `expireDueOrders` + `GET /orders` + `GET /orders/{id}` + availability cùng gặp một Order quá hạn) phát hiện hai lỗi: (1) `getActiveOrders`/`cancelUnpaidOrdersForEvent` nạp Order không khóa rồi mới khóa lại → persistence context giữ bản cũ → `ObjectOptimisticLockingFailureException` (500); (2) availability khóa Hold rồi tới Order, job khóa Order rồi tới Hold → deadlock. Chốt: chỉ lấy id, khóa Order theo id tăng dần, kiểm tra lại trạng thái sau khóa; luôn Order → Hold; job dùng SKIP LOCKED. Cân nhắc khác: retry khi optimistic lock fail (che lỗi, vẫn có deadlock), advisory lock theo event cho mọi đường đọc (tuần tự hóa availability — quá đắt). Giới hạn: job xử lý một lô trong một transaction; lô lớn giữ khóa lâu hơn (chấp nhận ở quy mô hiện tại).

### D24 — Giới hạn số vé mỗi Order
Roadmap/design P3 không quy định con số; trước đây không có giới hạn (một buyer giữ được toàn bộ kho GA trong 10 phút). Chọn **10 vé/Order**, cấu hình bằng `app.order.max-tickets-per-order` (biến môi trường `ORDER_MAX_TICKETS_PER_ORDER`), tính tổng quantity mọi item ACTIVE (ghế + GA). Lý do: đủ cho nhóm bạn/gia đình, nằm trong khoảng 4–10 các nền tảng bán vé phổ biến áp dụng cho sự kiện thường, và làm chậm hành vi gom vé trước khi có bot-detection (P6). Kiểm tra trong transaction đã khóa Order nên không vượt được bằng request song song; request tự vượt bị chặn trước khi gọi Product. Giới hạn hiện tại: theo Order (= theo buyer × event vì mỗi buyer tối đa một CART/event), chưa theo buyer qua nhiều Order PENDING_PAYMENT/PAID — cần xem lại khi có P4. Frontend chưa biết con số, chỉ hiển thị message lỗi `4012`.

### D25 — Retry, dead-letter và alert cho outbox
Trước: retry cố định 5s, vô hạn, không có tín hiệu khi kẹt. Chọn: backoff `base × 2^attempt` trần 5 phút (Kafka sập lâu thì không dội DB/log mỗi 5s), sau 10 lần (~25 phút) đánh dấu `dead_at` thay vì xóa (giữ dữ liệu để requeue tay bằng SQL) — "DLQ" là trạng thái trong cùng bảng chứ không phải topic DLQ riêng, vì lỗi ở phía producer (không gửi được) nên topic DLQ cũng không gửi được. Lô dừng ở lỗi đầu tiên; producer `max.block.ms` hạ từ 60s xuống 10s (`app.kafka.producer.max-block-ms`, commonlib-kafka — cũng giảm thời gian request user-service bị treo khi Kafka sập). Alert: gauge `outbox.pending`/`outbox.dead` và log `OUTBOX_DEAD_LETTER`; chưa có Prometheus/Alertmanager (P9) nên rule alert (`outbox_dead > 0`, `outbox_pending` tăng liên tục) mới ở mức tài liệu. Viết test phát hiện thêm lỗi: publisher product đọc `cancelledAt` bằng `getTimestamp()` không parse được `Instant.toString()` → mọi lần hủy event làm cả outbox kẹt; đã sửa.
