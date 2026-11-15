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
| D24 | Tối đa 10 vé mỗi Order (cấu hình được) | Chốt (chủ sản phẩm xác nhận 14/11/2026) |
| D25 | Outbox: backoff lũy thừa, dead-letter sau 10 lần, gauge + log ERROR làm tín hiệu alert | Chốt |
| D26 | OR08 (hủy giỏ) ở frontend: dùng cho CART, hoãn PENDING_PAYMENT tới P4 | Chốt (14/11/2026); phần PENDING_PAYMENT chốt ở D27 |
| D27 | Stripe Checkout Session; hạn thanh toán = deadline hold, Payment tự expire phiên; OR08 chỉ cho CART | Chốt (P4) |
| D28 | Thanh toán đến sau khi Order EXPIRED/CANCELLED → hoàn 100% tự động | Chốt (P4) |
| D29 | Hoàn tiền bất đồng bộ qua Kafka, hoàn cả đơn, vé về kho ngay khi ghi nhận | Chốt (P4) |
| D30 | API nội bộ Order → Payment dưới `/api/internal/**`, không route qua Gateway | Chốt (P4); cách ly mạng ở P9 |
| D31 | Vé phát hành cùng transaction ghi nhận thanh toán (→ TICKETS_ISSUED), 1 vé/người, mã ngẫu nhiên 192 bit | Chốt (P5) |
| D32 | Check-in bằng update có điều kiện theo event; quét trùng trả 4017 kèm ai quét/lúc nào | Chốt (P5) |
| D33 | Notification nghe event nghiệp vụ của Order (thêm `order.tickets-issued`, `order.refunded`), email gửi qua trạng thái trên bảng `notifications` | Chốt (P5) |
| D34 | Email người nhận lấy từ user-service qua API nội bộ role INTERNAL | Chốt (P5) |
| D35 | WebSocket thuần, xác thực bằng ticket một lần 30 giây, giới hạn Origin | Chốt (P5); nhiều instance cần Redis (P9) |
| D36 | QR sinh ở backend (zxing core), quét bằng BarcodeDetector hoặc nhập tay — không thêm dependency frontend | Chốt (chủ repo chọn 14/11/2026) |
| D37 | Device ID = UUID ngẫu nhiên ở localStorage, header `X-Device-Id`; order-service chỉ giữ/phát HMAC (`DEVICE_HASH_SECRET`) | Chốt (chủ repo chọn 15/11/2026) |
| D38 | `cart.item_added` thêm `deviceHash`, `eventViewedAt` (bổ sung field); "đã xem" ghi khi thiết bị gọi availability | Chốt (P6) |
| D39 | Feature tính theo `occurredAt` với cửa sổ Redis (sorted set, khóa theo bản tin); tuổi tài khoản từ user-service `/internal/users/{id}/account` | Chốt (P6) |
| D40 | Isolation Forest huấn luyện offline trên log k6, artifact commit vào repo; ngưỡng HIGH giới hạn ≤ 1% lần thêm giỏ của người dùng trên validation | Chốt (P6) |
| D41 | HIGH → `bot_flags` ACTIVE; chặn thêm giỏ theo buyer **hoặc** thiết bị (4019), không đụng hold; ADMIN review/gỡ (OR14/OR15) | Chốt (chủ repo chọn 15/11/2026) |
| D42 | Thiếu model → bot-detection vẫn tính feature, phát `UNSCORED`; Order chỉ phản ứng với HIGH | Chốt (P6) |
| D43 | Buyer hủy đơn có vé đã check-in: hoàn theo mốc thời gian cho **vé chưa dùng**, vé đã dùng không hoàn và không nhả kho; quote trả lý do | Chốt (chủ repo chọn 15/11/2026); bổ sung D29 |
| D44 | Check-in chỉ mở từ 3 giờ trước giờ bắt đầu tới giờ kết thúc sự kiện (4021) | Chốt (chủ repo chọn 15/11/2026) |
| D46 | Chặn khi HIGH kèm luật tay hoặc HIGH lần hai; HIGH đơn lẻ → `WATCH` (không chặn) | Chốt (15/11/2026); thay một phần D41 |
| D45 | Venue mới bắt buộc tọa độ, chọn từ kết quả tìm địa chỉ OpenStreetMap (Nominatim + Leaflet) | Chốt (chủ repo chọn 15/11/2026) |

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

### D26 — Dùng OR08 hủy giỏ ở frontend (chỉ CART)
Hiện trạng: `DELETE /api/orders/{id}` đã có và có test (hủy CART/PENDING_PAYMENT, nhả hold đúng một lần, idempotent), frontend chưa gọi; buyer muốn bỏ giỏ phải xóa từng item (OR05, item cuối → CANCELLED) hoặc chờ hết hạn 10 phút.
- **Lợi:** nhả ghế/GA ngay cho người khác thay vì giữ chết tới 10 phút — quan trọng nhất đúng lúc mở bán tranh chấp; một hành động rõ ràng thay cho xóa từng dòng.
- **Rủi ro:** (1) Với PENDING_PAYMENT, sau P4 buyer có thể hủy trong lúc Stripe đang xử lý → thanh toán thành công cho Order đã hủy. (2) Vòng giữ–hủy nhanh có thể bị bot dùng để "xoay" tồn kho (P6 cần tín hiệu này; P9 rate limit).
- **Khuyến nghị:** thêm nút "Hủy giỏ" (có hộp xác nhận) ở trang order detail **chỉ khi `status = CART`**; chưa hiển thị cho PENDING_PAYMENT. Khi thiết kế P4: hoặc giới hạn OR08 phía backend chỉ cho CART, hoặc bắt buộc hủy PaymentIntent thành công trước khi hủy Order (Payment xác nhận qua Kafka). Đưa sự kiện hủy giỏ vào feature P6 (`cart.cancelled` nếu cần).
- **Đã chốt 14/11/2026**: nút "Cancel cart" (hộp xác nhận "Keep cart"/"Cancel cart") ở trang order detail, chỉ hiện khi `status = CART`, chưa hết hạn, event chưa hủy; E2E `buyer.spec.ts` kiểm tồn được nhả ngay và giỏ PENDING_PAYMENT không có nút. Backend OR08 vẫn nhận PENDING_PAYMENT — **việc của P4**: giới hạn chỉ CART hoặc hủy PaymentIntent trước.


### D27 — Stripe Checkout Session và đồng bộ deadline
Roadmap để ngỏ "PaymentIntent/Checkout Session". Chọn **Checkout Session** (trang do Stripe host): frontend chỉ cần chuyển hướng tới `url`, không thêm `@stripe/stripe-js`, không có dữ liệu thẻ đi qua hệ thống. Đánh đổi: Stripe không cho đặt hạn phiên dưới 30 phút trong khi hold là 10 phút. Xử lý: Order gửi `holdExpiresAt` khi tạo phiên; `PaymentExpirationScheduler` gọi Stripe expire phiên khi quá hạn và phát `payment.failed (EXPIRED)`; nếu Stripe báo phiên đã thanh toán thì ghi nhận thành công (cũng là lưới an toàn khi webhook thất lạc). Không đặt `expires_at` phía Stripe vì tham số phải giống hệt giữa các lần retry cùng idempotency key. **Không gia hạn hold khi confirm/checkout** (giữ D09); buyer xác nhận sát giờ mà trả không kịp được hoàn theo D28 — có cấp "cửa sổ thanh toán" riêng hay không vẫn là câu hỏi sản phẩm mở. Khởi tạo thanh toán idempotent theo `order_id` (UNIQUE + Stripe Idempotency-Key `checkout-{paymentId}`) thay cho Idempotency-Key của client: phủ được cả nhiều tab/thiết bị. Chốt nốt D26: **OR08 chỉ nhận CART**, `PENDING_PAYMENT` trả 4005 — buyer có thể đang trả tiền trên Stripe; bỏ thanh toán thì Order tự hết hạn (≤ 10 phút). Phương án "hủy phiên Stripe rồi mới hủy Order" bị loại vì thêm một vòng gọi đồng bộ mà D28 đã bao phủ rủi ro còn lại.

### D28 — Thanh toán trễ thì hoàn tiền, không hồi sinh Order
"Payment thành công đến sau deadline hold" (việc treo từ P3). Khi nhận `payment.succeeded`, Order khóa dòng và rẽ theo trạng thái: `PENDING_PAYMENT` → `PAID` (kể cả vừa quá deadline nhưng job chưa expire — hold vẫn còn nên không bán trùng); `EXPIRED`/`CANCELLED` → giữ nguyên, tạo refund `LATE_PAYMENT` 100% theo số tiền Payment báo đã thu; trạng thái đã thanh toán → bỏ qua (bản tin lặp). Loại phương án "giữ lại vé nếu còn trống": phải giữ lại đúng ghế/tồn trong khi người khác có thể đang giữ, phức tạp và dễ sai ngay ở lớp chống bán trùng. Order vẫn giữ TTL riêng (job 1 giây), không phụ thuộc tín hiệu của Payment (roadmap mục 2).

### D29 — Hoàn tiền qua Kafka, hoàn cả đơn
Roadmap để ngỏ sync/async cho Order → Payment khi hoàn. Chọn async: Order ghi `order_refunds` + outbox `order.refund-requested` trong cùng transaction đổi trạng thái; Payment consume, gọi Stripe (Idempotency-Key `refund-{refundId}`), phát `refund.completed`/`refund.failed`. Lý do: hai trong ba nguồn hoàn (hủy event, thanh toán trễ) phát sinh **bên trong consumer**, xử lý hàng loạt — gọi REST đồng bộ trong transaction đó là cascading failure; một đường duy nhất cho cả ba nguồn dễ kiểm chứng hơn. Phạm vi P4: **hoàn cả đơn**, `order_refunds.order_id` UNIQUE; hoàn từng vé để sau khi có vé/QR (P5). Vé trả về kho **ngay khi ghi nhận yêu cầu** (buyer đã từ bỏ vé; event hủy thì tồn kho không còn ý nghĩa) thay vì chờ `refund.completed` — nếu Stripe hoàn lỗi thì đó là việc đối soát tiền (`REFUND_FAILED`, Order giữ `REFUND_PENDING`), không giữ chết vé. Payment không tính %: chỉ kiểm tra tổng hoàn ≤ số đã thu. Mốc 0% (< 24 giờ) bị từ chối (4015) thay vì hủy đơn không hoàn tiền. Giới hạn: refund `FAILED` chưa có API xử lý lại; Stripe lỗi tạm thời được retry vô hạn mỗi 30 giây (tiền nợ buyer — bỏ cuộc âm thầm tệ hơn thử mãi).

### D30 — API nội bộ giữa service
PM01 (`/api/internal/payments/checkout-sessions`) chỉ dành cho order-service. Cân nhắc: (a) secret dùng chung giữa service — thêm một secret phải quản lý, mà mô hình hiện tại vốn đã tin mạng nội bộ (D03); (b) đặt dưới prefix không có route ở Gateway + chuyển tiếp `X-User-Id`/`X-User-Role` để Payment vẫn kiểm tra `buyerId` khớp danh tính — chọn (b). Giới hạn giống mọi service khác: ở dev cổng 9002 truy cập trực tiếp được; network isolation ở P9 mới thật sự đóng lại. Quy ước từ nay: API chỉ cho service gọi đặt dưới `/internal/**`, không thêm vào `routes`/`public-endpoints` của Gateway.


### D31 — Phát hành vé cùng lúc ghi nhận thanh toán
Roadmap tách `PAID` và `TICKETS_ISSUED` nhưng không nói khi nào chuyển. Cân nhắc: (a) phát hành bằng consumer/job riêng sau `PAID` — có khoảng "đã trả tiền mà chưa có vé" và thêm một đường phải retry; (b) phát hành trong chính transaction xử lý `payment.succeeded` — chọn (b): Order đi thẳng `PENDING_PAYMENT → TICKETS_ISSUED`, `PAID` chỉ còn là trạng thái trung gian trong transaction (giữ trong enum/CHECK cho dữ liệu cũ của P4). Ghế = 1 vé, GA số lượng N = N vé để mỗi người vào cổng một mã. Mã là 24 byte `SecureRandom` (base64url, 32 ký tự — đủ ngắn để nhập tay), UNIQUE ở DB; không suy ra từ id (HMAC) để không phải giữ thêm secret, đổi lại phải lưu mã. Hệ quả cho hoàn tiền (D29 hoàn cả đơn): đơn có vé đã check-in thì buyer không hoàn được (4015); ADMIN hủy event vẫn hoàn 100% (D16), vé chưa dùng thành `VOID`, vé đã check-in giữ làm lịch sử.

### D32 — Check-in nguyên tử, phạm vi theo event
Đọc trạng thái rồi mới ghi cho phép hai cổng cùng cho một vé qua. Chốt: `UPDATE tickets SET CHECKED_IN … WHERE code = ? AND event_id = ? AND status = 'VALID'` — đúng một request thắng (test 10 lần quét song song → 1 thành công). Nhánh thua đọc lại để báo lý do: đã check-in → 4017 với `result` là lần quét đầu (ai — userId, lúc nào) để STAFF xử lý tại chỗ (roadmap P5 mục 3); `VOID` → 4018. Request bắt buộc `eventId`: vé của sự kiện khác báo 4016 như mã không tồn tại, để STAFF sự kiện A không dò được vé sự kiện B và vé không bị "đốt" nhầm ở cổng khác. Giới hạn: "ai quét" chỉ là userId (Order không có tên STAFF).

### D33 — Notification nghe Order, email qua trạng thái trên bảng
Notification **không** nghe `payment.succeeded`: tiền vào chưa chắc là đặt vé thành công (D28), và `refund.completed` của Payment không có buyerId (Payment chỉ biết tiền, D07). Order phát thêm `order.tickets-issued` (không chứa mã vé) và `order.refunded` (khi nhận `refund.completed`); Notification dùng lại `order.refund-requested` (consumer group thứ hai). Đổi schema event đang dùng bị tránh — chỉ thêm topic. Mỗi bản tin nguồn → tối đa một thông báo nhờ `dedup_key` UNIQUE + `ON CONFLICT DO NOTHING`. Email không gửi trong consumer mà đánh dấu `PENDING` trên chính dòng thông báo; `EmailDispatchScheduler` giành dòng (update có điều kiện), gửi ngoài transaction, backoff `30s × 2^n`, tối đa 5 lần rồi log `NOTIFICATION_EMAIL_GAVE_UP` — cùng tinh thần outbox D25, consumer Kafka không bị SMTP chậm chặn, thông báo trong ứng dụng không phụ thuộc email. SMTP chưa cấu hình → `SKIPPED`. Email tài khoản P1 (OTP, mật khẩu tạm) chứa bí mật nên không lưu DB, gửi thẳng trong consumer, lỗi thì để Kafka retry; group riêng với `auto.offset.reset=latest` để lần đầu chạy không gửi lại mọi OTP tồn đọng từ P1, và bỏ OTP cũ hơn 10 phút (đã hết hạn).

### D34 — Lấy email từ user-service
Cân nhắc: (a) Notification giữ bản sao user (email/tên) từ event — phải thêm event cho mọi đường tạo/sửa user (register, Google, seed, STAFF) và nhân bản PII; (b) Order đưa email vào event — Order không có email; (c) gọi user-service lúc gửi — chọn (c), theo quy ước `/internal/**` của D30: `GET /api/internal/users/{id}/contact`, `@PreAuthorize("hasRole('INTERNAL')")`. Role `INTERNAL` không thể đến từ client vì Gateway luôn xóa `X-User-*` và JWT chỉ mang BUYER/ADMIN/STAFF; truy cập thẳng cổng 9004 ở dev vẫn là giới hạn chung của P9. Kèm theo: user-service trước đây **chưa bật `@EnableMethodSecurity`** nên `@PreAuthorize` trên `POST /users/staff` không có tác dụng (vẫn an toàn nhờ rule URL `hasRole("ADMIN")`); P5 bật lên.

### D35 — Xác thực WebSocket bằng ticket một lần
Trình duyệt không gửi được header `Authorization` khi mở WebSocket. Cân nhắc: (a) JWT trên query string — token sống 15 phút dễ lọt vào log Gateway/proxy; (b) service tự verify JWT trong STOMP CONNECT — phá D03 (chỉ Gateway verify JWT) và cần thư viện STOMP ở frontend; (c) ticket ngẫu nhiên 256 bit, sống 30 giây, dùng một lần, cấp qua `POST /api/notifications/ws-ticket` (đi qua Gateway, JWT hợp lệ) — chọn (c). Handshake `GET /api/notifications/ws` công khai ở Gateway, interceptor lấy-và-xóa ticket; `setAllowedOrigins(FRONTEND_ORIGIN)` chặn cross-site WebSocket hijacking. Dùng WebSocket thuần thay STOMP vì kênh chỉ một chiều server → client. Giới hạn: ticket và phiên nằm trong bộ nhớ — đúng cho một instance; nhiều instance cần Redis cho ticket + pub/sub (P9).

### D36 — QR sinh ở backend
Frontend chưa có thư viện QR; quy tắc dự án yêu cầu hỏi trước khi thêm dependency. Chủ repo chọn (14/11/2026): backend sinh PNG bằng `com.google.zxing:core` 3.5.4 (tự ghi PNG bằng `ImageIO`, không kéo module `javase` vào runtime; `javase` chỉ ở scope test để đọc ngược QR), API trả `Cache-Control: no-store`. STAFF quét bằng `BarcodeDetector` của trình duyệt (Chrome/Edge/Android) hoặc nhập/dán mã (máy quét USB/Bluetooth gõ như bàn phím). Phương án bị loại: thêm `qrcode` + `@zxing/browser` ở frontend (2 dependency), hoặc bỏ QR khỏi P5.

### D37 — Device ID ngẫu nhiên, chỉ lưu HMAC
Roadmap cần "số tài khoản trên cùng thiết bị" và "chặn lần thêm giỏ tiếp theo từ cùng thiết bị", nhưng hệ thống chưa nhận diện thiết bị nào. Cân nhắc: (a) thư viện fingerprint trình duyệt (FingerprintJS…) — ổn định hơn nhưng thêm dependency, thu thập đặc điểm trình duyệt (nặng phần P8); (b) UUID ngẫu nhiên do frontend sinh một lần, lưu localStorage; (c) bỏ tín hiệu thiết bị. Chủ repo chọn (b) (15/11/2026). Order nhận header `X-Device-Id`, chỉ chấp nhận định dạng UUID (sai/thiếu = không có thiết bị, không lỗi), băm `HMAC-SHA256(DEVICE_HASH_SECRET, id)` ngay ở filter; ID thô không vào DB, Kafka hay log. Secret trống → sinh ngẫu nhiên mỗi lần khởi động + WARN (dev chạy được, đổi lại hash không ổn định). Giới hạn: bot xóa storage hoặc không gửi header là thoát tín hiệu thiết bị — nên chặn theo cả tài khoản (D41) và có feature `has_device`.

### D38 — Bổ sung field cho `cart.item_added`
Feature "không xem chi tiết sự kiện trước khi thêm giỏ" cần dấu vết xem trang; chỉ Order thấy cả lượt xem (availability) lẫn thêm giỏ. Order ghi lần đầu thiết bị gọi OR07 (`SET NX`, TTL 2 giờ, best-effort) và gửi `eventViewedAt` cùng `deviceHash` trong `cart.item_added`. Chỉ **thêm** field (topic chưa có consumer trước P6); bản tin chuyển sang cột `payload` JSON của outbox như event P4/P5, bản ghi cũ dạng cột vẫn gửi được. Trang chi tiết sự kiện gọi availability (hiển thị "N left" cho GA) để người mua GA cũng có tín hiệu. Giới hạn: bot gọi availability trước là "đã xem".

### D39 — Cửa sổ feature theo thời điểm bản tin
bot-detection giữ sorted set trong Redis (buyer/thiết bị → lần thêm giỏ, thiết bị → tài khoản, buyer → sự kiện), score = epoch ms của `occurredAt`, member = khóa bản tin (`orderId:occurredAt:itemCount:quantity`) nên Kafka phát lại không đếm trùng; cùng ngữ nghĩa được cài trong bộ nhớ để tái hiện log offline (test so khớp hai bản). Tuổi tài khoản: Order không có, gửi trong JWT phải đổi hợp đồng auth → gọi user-service `/internal/users/{id}/account` (role INTERNAL, chỉ `createdAt`, không PII), cache 24 giờ; lỗi tạm thời → retry bản tin; user không tồn tại → giá trị thay thế (median của tập train).

### D40 — Huấn luyện offline, commit artifact
Dữ liệu sinh bằng k6 trên stack thật (`*_e2e`), log lấy từ outbox (payload đúng như đã gửi Kafka), tái hiện qua đúng code feature. Vòng A = train/validation (chia theo tài khoản), vòng B = test (tài khoản và lần chạy khác). Kịch bản cho bot sinh nhiều lần thêm giỏ hơn người dùng; Isolation Forest giả định bất thường là thiểu số nên tập train được lấy mẫu để bot ≈ 10% — bước dùng nhãn, ghi rõ trong báo cáo cùng kết quả của model học trên tỉ lệ tự nhiên. Ngưỡng: HIGH = phân vị 99% điểm của người dùng trên validation (≤ 1% lần thêm giỏ của người dùng bị chặn), MEDIUM = phân vị 95%. Artifact (`model/isolation_forest.joblib` + `model_meta.json` có danh sách feature, ngưỡng, chỉ số) commit vào repo: nhỏ, tái lập được bằng `training/`; service từ chối model có danh sách feature khác code.

### D41 — Phản ứng của Order với điểm rủi ro
Chỉ `HIGH` tạo flag; một dòng ACTIVE mỗi buyer (partial unique index, upsert gộp `hit_count`, giữ điểm cao nhất). OR01/OR04/OR06 (tăng số lượng) bị chặn `4019` nếu buyer **hoặc** thiết bị có flag ACTIVE — kiểm tra trước khi gọi Product; giảm số lượng, xem, thanh toán giỏ hiện có vẫn được (không hủy hold, roadmap). Chặn theo cả hai vì bot đổi được thiết bị (D37) và một thiết bị có thể chạy nhiều tài khoản. ADMIN gỡ (OR15) → CLEARED, giữ lịch sử; điểm của lần thêm giỏ xảy ra trước lúc gỡ bị bỏ qua để Kafka phát lại không khóa lại. Không tự gỡ theo thời gian (chủ repo chọn có trang review). Không gắn cờ ở MEDIUM: chỉ hiển thị trong log điểm.

### D42 — Chế độ UNSCORED
Chưa có model (lần đầu triển khai, hoặc model bị từ chối vì lệch feature) → service vẫn consume, tính feature và phát `riskLevel = UNSCORED`, `anomalyScore = null`. Nhờ vậy chính pipeline online được dùng để thu dữ liệu, và Order không bao giờ chặn khi model chưa sẵn sàng (fail open).

### D43 — Hoàn tiền vé chưa dùng khi đơn có vé đã check-in
Phát hiện khi chạy thử: đơn 4 vé có 2 vé đã quét (quét sớm 5 ngày vì check-in chưa giới hạn giờ) thì buyer không hoàn được gì, và UI báo nhầm lý do "< 24 giờ". D29 (hoàn cả đơn) chặn cả đơn để không hoàn tiền vé đã dùng. Chủ repo chọn (15/11/2026): hoàn **vé chưa dùng** theo mốc thời gian, vé đã check-in không hoàn. Số tiền = % × tổng giá snapshot của vé `VALID`. Chỉ nhả tồn kho phần chưa dùng: ghế đã có người ngồi giữ `PURCHASED` (nếu nhả, ghế có thể bị bán lại), hold GA giảm `quantity` theo số vé chưa dùng. Vẫn một refund mỗi Order (sau lần hoàn này không còn vé `VALID`), Payment không đổi vì vốn đã cho hoàn một phần số tiền đã thu. OR10 trả `reason` (`NOT_PAID`, `ALREADY_REFUNDED`, `TOO_CLOSE_TO_EVENT`, `ALL_TICKETS_USED`), `refundableTickets`, `usedTickets` để UI không phải đoán. ADMIN hủy event giữ D16 (hoàn 100% cả đơn). Với D44, buyer gần như không còn gặp tình huống này (check-in mở ≤ 3 giờ trước, hoàn chỉ được ≥ 24 giờ trước) — D43 chủ yếu xử lý dữ liệu cũ và giữ đúng nếu khung check-in nới ra. Giới hạn: event bị hủy sau khi buyer đã hoàn một phần thì không hoàn thêm (một refund/Order).

### D44 — Khung giờ check-in
Trước đây STAFF quét được bất cứ lúc nào — vé có thể bị đánh dấu đã dùng nhiều ngày trước sự kiện (nhầm sự kiện, thử máy quét…). Chủ repo chọn: từ 3 giờ trước giờ bắt đầu (`app.order.check-in-opens-before`, mặc định `PT3H`) tới giờ kết thúc (event không có giờ kết thúc: +12 giờ). Ngoài khung → `4021 CHECK_IN_NOT_OPEN` kèm `{opensAt, closesAt}`, vé không đổi trạng thái. Order hỏi Product giờ sự kiện mỗi lần quét (fail closed 4010); event không tồn tại báo `4016` như vé không có (giữ D32). Trang `/check-in` hiển thị card các sự kiện trong 7 ngày tới, chỉ mở quét cho sự kiện đã tới khung; quét liên tục, không cần bấm "quét vé kế".

### D45 — Tọa độ venue từ OpenStreetMap
Địa chỉ venue gõ tay có thể không tồn tại. Chủ repo chọn OpenStreetMap: Leaflet hiển thị bản đồ, Nominatim tìm địa chỉ (chỉ khi bấm tìm, tối đa 5 kết quả — theo chính sách sử dụng của Nominatim, không autocomplete), không cần API key. ADMIN phải chọn một kết quả; frontend điền địa chỉ/thành phố và gửi `latitude`/`longitude`. Backend bắt buộc tọa độ hợp lệ với venue mới (migration `V5__venue_coordinates.sql`, venue cũ giữ NULL), lưu làm tròn 6 chữ số. Backend **không** tự gọi Nominatim để kiểm lại (thêm phụ thuộc mạng ra ngoài cho product-service) — tọa độ chứng minh địa chỉ đã được chọn từ dữ liệu bản đồ, không chống được ADMIN cố ý gửi tọa độ giả qua API. Đánh đổi: thêm dependency `leaflet` ở frontend; tile và tìm kiếm phụ thuộc máy chủ công cộng của OSM (giới hạn ~1 request/giây, đủ cho thao tác của ADMIN).

### D46 — Chặn khi HIGH có thêm bằng chứng
Báo cáo P6: chặn ngay ở lần HIGH đầu (D41) làm 7/200 tài khoản người dùng mô phỏng ở tập test bị chặn nhầm. (Con số 8% của lần chạy live hóa ra do UUID thiết bị trùng giữa hai lần chạy k6 — xem báo cáo mục 4.) Cân nhắc trên tập A/B (thu khi chưa chặn ai): (a) chỉ luật tay — không chặn nhầm nhưng mất phần model bắt thêm; (b) cần ≥ 2 HIGH — bỏ sót bot chỉ thêm giỏ 1–2 lần (botfarm 7/24); (c) HIGH **và** luật tay, hoặc HIGH lần hai — chọn (c): test B 0/200 người dùng bị chặn, bot 47/56 (D41: 49/56 + 7 người dùng). bot-detection gửi thêm `ruleMatched` (`buyer_adds_1m ≥ 4` hoặc `device_accounts_24h ≥ 4` khi có thiết bị); Order: HIGH kèm luật → `ACTIVE`; không kèm → `WATCH` (ADMIN thấy, không chặn); đã có dòng mở mà gặp HIGH nữa → `ACTIVE`. Một dòng mở (WATCH/ACTIVE) mỗi buyer (migration V6). Bản tin cũ không có `ruleMatched` coi là `false`. Đánh đổi: bot lọt thêm khoảng 1 lần thêm giỏ trước khi bị chặn.

