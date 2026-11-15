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
