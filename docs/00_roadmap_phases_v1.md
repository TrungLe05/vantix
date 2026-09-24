# Vantix — Nền tảng bán vé sự kiện: Master Roadmap & Quyết định thiết kế

> **Phiên bản:** v1.0
> **Ngày:** tháng 9/2026
> **Mục đích tài liệu:** Đây là tài liệu gốc (master) mô tả toàn bộ quyết định kiến trúc đã thống nhất qua thảo luận cho dự án Vantix — nền tảng bán vé sự kiện tích hợp phát hiện bất thường và trợ lý AI. Mỗi phase bên dưới được viết đủ chi tiết để có thể tách riêng, dùng làm input cho việc thiết kế API, ERD, sequence diagram của phase đó mà không cần đọc lại toàn bộ lịch sử thảo luận.

---

## 0. Tổng quan dự án

### 0.1 Mô tả

Vantix là một nền tảng thương mại điện tử bán vé sự kiện (concert, hội thảo, thể thao...), hỗ trợ cả vé có sơ đồ ghế (seat map) và vé đứng theo số lượng (General Admission), tích hợp:

- Cơ chế giữ chỗ và xử lý đồng thời để chống bán trùng vé khi mở bán.
- Một mô hình học máy không giám sát để phát hiện hành vi mua vé bất thường (bot).
- Một trợ lý hỏi đáp dùng AI để hỗ trợ người mua.
- Thanh toán qua Stripe (chế độ test/sandbox).

### 0.2 Nguyên tắc xuyên suốt khi ra quyết định

Thứ tự bắt buộc khi thiết kế: **Yêu cầu nghiệp vụ → Service boundaries → Chọn công nghệ**. Không chọn công nghệ trước khi biết bài toán cần gì.

---

## 1. Yêu cầu chức năng (Functional Requirements v1 — đã chốt)

| # | Yêu cầu | Chi tiết đã chốt |
|---|---|---|
| FR1 | Vai trò người dùng | Giai đoạn đầu: **BUYER** (mua vé), **ADMIN** (tạo sự kiện, quản trị), **STAFF** (quét vé check-in, do ADMIN tạo tài khoản). **SELLER** (ban tổ chức tự đăng sự kiện) để dành cho giai đoạn sau. |
| FR2 | Giỏ hàng | Cho phép nhiều loại vé/số lượng trong **một đơn hàng**, nhưng **giới hạn trong một sự kiện** (không gộp nhiều sự kiện vào một giỏ). |
| FR3 | Loại vé | Hỗ trợ cả hai trong cùng một sự kiện: **vé ghế có số (seat map)** và **vé đứng GA (General Admission)** — GA là một kho số lượng phẳng, không chia khu vực con ở giai đoạn này. |
| FR4 | Chính sách hoàn/hủy vé | 3 mốc: <br>• ≥ 7 ngày trước giờ diễn: hoàn 100%<br>• 24 giờ – 7 ngày trước giờ diễn: hoàn 50%<br>• < 24 giờ trước giờ diễn: hoàn 0% (có thể chuyển nhượng thay vì hoàn tiền)<br>• Nếu ban tổ chức (ADMIN) hủy sự kiện: hoàn 100% bất kể thời điểm. |
| FR5 | Thanh toán | Qua **Stripe (chế độ test/sandbox)**, dùng tài khoản Stripe US do sandbox không hỗ trợ chọn quốc gia Việt Nam → giá niêm yết bằng **USD**. |
| FR6 | Phát hiện bất thường | Không dùng fraud detection có giám sát (không có dataset nhãn thật khả thi cho hành vi mua vé). Thay bằng: **mô hình không giám sát (Isolation Forest)** phát hiện hành vi bot ở bước **thêm vé vào giỏ hàng**, huấn luyện trên **dữ liệu tự tạo** (script giả lập bot bằng k6 + traffic người dùng thật). |
| FR7 | Trợ lý hỏi đáp | Một trợ lý dùng **LLM API** (không tự huấn luyện) trả lời câu hỏi của người mua về sự kiện, chính sách hoàn/hủy, trạng thái đơn hàng của chính họ. |
| FR8 | Check-in | Quét mã vé lúc vào cổng, phát hiện quét trùng. Chỉ **ADMIN và STAFF** được thực hiện. |
| FR9 | Thông báo | Email + thông báo trong ứng dụng (WebSocket) khi có sự kiện quan trọng (đặt vé thành công, hủy, cảnh báo). |

### Ngoài phạm vi hiện tại (để dành giai đoạn sau)

- SELLER tự đăng sự kiện + luồng ADMIN duyệt
- GA chia theo khu vực con
- Bán lại / chuyển nhượng vé giữa người dùng
- Admin/Fraud Analyst Dashboard đầy đủ

---

## 2. Service Boundaries (v1 — đã chốt qua thảo luận)

Nguyên tắc dùng để tách service: **ai sở hữu dữ liệu này**, **tốc độ thay đổi khác nhau đến đâu**, **có cần hỏi ngược service khác để hoàn thành việc của mình không**.

| Service | Trách nhiệm chính | Lý do tách riêng |
|---|---|---|
| **api-gateway** | Entry point duy nhất ra internet, verify JWT, inject `X-User-Id`/`X-User-Role`, routing, rate limiting | Hạ tầng lõi, thay đổi rất hiếm, không nên gánh logic nghiệp vụ hoặc ML (khác tốc độ thay đổi, khác ngôn ngữ nếu ML là Python) |
| **user** | Đăng ký, đăng nhập, quản lý người dùng, JWT, OAuth Google, tạo tài khoản STAFF (bởi ADMIN) | Mọi luồng khác đều cần xác thực trước; vòng đời thay đổi độc lập với nghiệp vụ bán vé |
| **product** | Catalog: sự kiện (event), địa điểm (venue), sơ đồ ghế (seat map), loại vé (ticket type: SEATED/GA), giá gốc | Đọc nhiều, ghi ít (chỉ ADMIN thiết lập) — khác hẳn workload của Order |
| **order** | Giỏ hàng, giữ chỗ (hold, có state transitions liên tục), sinh vé/QR sau khi nhận tín hiệu thanh toán thành công, xử lý hoàn/hủy (tính %), check-in/quét vé | Ghi nhiều, nhiều trạng thái, **sở hữu đầy đủ thông tin đơn hàng** (ai mua, vé nào, số lượng) mà không cần hỏi ngược service khác |
| **payment** | Gọi Stripe tạo phiên thanh toán, nhận webhook, ghi nhận trạng thái tiền, thực hiện hoàn tiền qua Stripe, phát tín hiệu thành công/thất bại | Chỉ biết "tiền", **không biết "vé là gì"** — cô lập rủi ro khi đổi cổng thanh toán hoặc mở rộng sản phẩm khác ngoài vé |
| **bot-detection** | Mô hình ML (Isolation Forest) chấm điểm hành vi đáng ngờ, được Order gọi mỗi khi có hành động thêm giỏ hàng | Tốc độ thay đổi và stack công nghệ (Python) khác hẳn phần còn lại (Java); vòng đời huấn luyện lại độc lập với code nghiệp vụ |
| **notification** | Consume sự kiện từ các service khác, gửi email, đẩy thông báo real-time qua WebSocket, lưu bảng `notifications` | Tách để các service khác không phải tự lo việc gửi thông báo, dễ mở rộng kênh thông báo sau này |
| **assistant** | Trợ lý hỏi đáp LLM, lấy ngữ cảnh (đơn hàng của chính người hỏi, thông tin sự kiện, chính sách) qua API sang Order/Product rồi gọi LLM API | Độc lập, stateless, ít phụ thuộc, không cần DB riêng |

### Ghi chú stateless

`api-gateway` và `assistant` **không có database riêng** — chúng không lưu trạng thái nghiệp vụ lâu dài.

### Backlog cần quyết ở bước thiết kế API/luồng chi tiết (chưa ảnh hưởng ranh giới service)

1. **Ai thực thi thao tác trừ tồn kho:** Order tự trừ trong DB của mình khi tạo hold, hay gọi API sang Product để Product trừ? (Cần chốt ở P3, ảnh hưởng thiết kế bảng dữ liệu Order/Product.)
2. **Luồng hoàn tiền hai chiều Order ↔ Payment:** Order tính % hoàn theo chính sách → gọi Payment thực hiện hoàn qua Stripe → Payment báo kết quả → Order cập nhật trạng thái. (Chi tiết hóa ở P4.)
3. **Cơ chế nhả hold khi Payment thất bại/mất tín hiệu:** Cần cả tín hiệu chủ động (event từ Payment) **và** TTL độc lập ở Order làm lưới an toàn, không chỉ trông chờ vào tín hiệu từ Payment.
4. **Vai trò STAFF được tạo bởi ADMIN** (đã chốt) — cần thiết kế endpoint tương ứng ở `user` service.

---

## 3. Quyết định công nghệ (Tech Stack v1)

### 3.1 Ngôn ngữ/Framework

| Service | Ngôn ngữ | Lý do |
|---|---|---|
| api-gateway, user, product, order, payment, notification | **Java 21 + Spring Boot 3.x** | Hệ sinh thái Spring (Spring Data/JPA/Hibernate, Spring Security, Kafka client chính thức) giúp tập trung vào logic nghiệp vụ |
| bot-detection | **Python + FastAPI** (scikit-learn cho Isolation Forest) | Hệ sinh thái ML mạnh nhất hiện tại; ràng buộc từ chính bài toán, không phải lựa chọn tùy ý |
| assistant | Ngôn ngữ linh hoạt (Python hoặc Java đều được) | Chủ yếu là gọi LLM API + gọi API nội bộ lấy ngữ cảnh, không có ràng buộc công nghệ đặc biệt |

### 3.2 Kiến trúc mã nguồn

- **Monorepo**: mỗi service là một module trong cùng một repository.
- **common-lib** cho các service Java: áp dụng **"rule of 3"** — chỉ tách một đoạn logic vào common-lib khi có **từ 3 service trở lên** cùng cần dùng. Common-lib được **chia thành các artifact/module riêng biệt** (ví dụ: `commonlib-security`, `commonlib-kafka`, `commonlib-api-response`) để một service không bị kéo theo các dependency nó không dùng đến.

### 3.3 Giao tiếp giữa các service (Sync vs Async)

| Luồng | Kiểu | Lý do |
|---|---|---|
| Order → bot-detection | **Bất đồng bộ (Kafka)** | Không làm chậm/nghẽn thao tác thêm giỏ hàng (hành động rất thường xuyên); kết quả rủi ro cao dẫn tới **chặn các lần thêm tiếp theo từ cùng thiết bị + đánh dấu review**, **không hủy hold hiện tại** (tránh gây khó dễ oan cho người dùng thật nếu model đánh giá sai) |
| Order → Payment (tạo phiên thanh toán) | **Đồng bộ (REST)** | Chỉ cần lấy nhanh link/thông tin phiên thanh toán để tiếp tục, không phải để biết "tiền đã vào chưa" |
| Stripe → Payment (webhook) | Đồng bộ về mặt kỹ thuật (HTTP), nhưng **độc lập thời điểm** | Payment không kiểm soát được khi nào Stripe gọi webhook tới (có thể sau vài giây tới vài phút, đặc biệt khi cần xác thực 3D Secure). **Webhook handler bắt buộc phải idempotent** vì Stripe có thể gửi lại cùng một webhook nhiều lần |
| Payment → Order (báo kết quả thanh toán) | **Bất đồng bộ (Kafka)** | Order không thể "đứng chờ" một sự kiện không rõ khi nào xảy ra; gọi đồng bộ ở đây sẽ giữ thread/connection không cần thiết, có nguy cơ cascading failure nếu Payment/Stripe chậm |
| Order → Notification | **Bất đồng bộ (Kafka)** | Người dùng không cần thấy thông báo ngay trong cùng một request |

**Nguyên tắc chung rút ra:** đồng bộ chỉ dùng khi cần một phản hồi ngắn, nhanh, để tiếp tục luồng hiện tại ngay; bất đồng bộ dùng khi kết quả phụ thuộc vào một hệ thống/hành động có thời điểm không kiểm soát được (ví dụ chờ người dùng thao tác trên trang Stripe), hoặc khi việc chờ không cần thiết cho trải nghiệm người dùng.

### 3.4 Lưu trữ dữ liệu

- **Database-per-service** cho mọi service có state nghiệp vụ (user, product, order, payment, notification), mỗi service một PostgreSQL riêng. Không có DB dùng chung.
- **api-gateway** và **assistant**: stateless, không có DB riêng.
- **Redis**: dùng cho trạng thái tạm thời có TTL (hold giữ chỗ ở Order). Đặt tiền tố key rõ ràng theo mục đích (ví dụ `hold:`, `otp:`) để tránh nhầm lẫn giữa các loại dữ liệu.

### 3.5 Cơ chế đảm bảo đúng đắn khi đồng thời cao (chống bán trùng vé)

Đây là ba cơ chế **giải quyết ba vấn đề khác nhau**, cần dùng cả ba, không thay thế lẫn nhau:

1. **Idempotency key** (áp dụng ở bước **khởi tạo thanh toán**): chống trường hợp **cùng một client** gửi lại cùng một request nhiều lần (do mạng chậm, do retry). Không giải quyết việc hai người khác nhau tranh cùng một ghế.
2. **Redis TTL cho hold**: lớp giữ chỗ nhanh, nhưng **không phải ràng buộc tuyệt đối** — có thể sai nếu hai request đến gần như cùng lúc hoặc Redis gặp sự cố.
3. **Ràng buộc/update nguyên tử ở tầng DB của Order** (ví dụ: unique constraint trên `(event_id, seat_id)` khi trạng thái HELD/SOLD, hoặc `UPDATE ... WHERE status = 'AVAILABLE'`): đây là **lớp đảm bảo cuối cùng**, chống được việc hai người khác nhau cùng giữ được một ghế, độc lập với việc Redis có hoạt động đúng hay không.

---

## 4. Danh sách Phase (P0 → P9)

> Mỗi phase dưới đây được viết đủ chi tiết (mục tiêu, quyết định đã có sẵn, việc cần làm, tài liệu cần sinh ra, tiêu chí hoàn thành) để có thể dùng độc lập làm input cho bước thiết kế API/ERD/sequence diagram chi tiết hơn của chính phase đó.
>
> **Frontend đi kèm theo từng phase** (mỗi phase có một lát cắt giao diện để demo/test được ngay), không dồn về một phase riêng ở cuối.

---

### P0 — Nền tảng & hạ tầng

**Mục tiêu:** Dựng khung chạy được cho toàn bộ 8 service, chưa có logic nghiệp vụ.

**Quyết định đã có sẵn:**
- Monorepo, mỗi service là một module.
- common-lib theo rule-of-3, tách nhiều artifact nhỏ.
- Docker Compose cho hạ tầng dùng chung: PostgreSQL (nhiều DB, mỗi service một DB), Redis, Kafka.

**Việc cần làm:**
1. Khởi tạo repo, cấu trúc thư mục: `services/api-gateway`, `services/user`, `services/product`, `services/order`, `services/payment`, `services/bot-detection`, `services/notification`, `services/assistant`, `services/common-lib/*`.
2. `common-lib` module: bắt đầu **rỗng hoặc tối thiểu** (ví dụ chỉ `ApiResponse`, `ErrorCode` interface) — chỉ thêm phần dùng chung khi thực sự có ≥ 3 service cần, đúng rule-of-3 đã thống nhất.
3. Docker Compose: PostgreSQL, Redis, Kafka, pgAdmin, Kafka UI, healthcheck cho từng dịch vụ hạ tầng.
4. CI cơ bản (GitHub Actions): build + test mỗi service khi có PR.
5. Khung service rỗng cho cả 8 service, mỗi service có endpoint `/actuator/health` (hoặc tương đương cho Python) chạy qua gateway.

**Tài liệu cần sinh ra ở bước sau:**
- `01_system_architecture.md`: sơ đồ tổng thể 8 service, luồng request qua gateway.
- Cấu trúc thư mục monorepo cụ thể.

**Tiêu chí hoàn thành:** `docker-compose up` chạy sạch; mỗi service (khung rỗng) start thành công; gọi được `/actuator/health` của từng service qua gateway.

---

### P1 — User & Auth

**Mục tiêu:** Đăng ký/đăng nhập, gateway xác thực JWT tập trung, có vai trò BUYER/ADMIN/STAFF.

**Quyết định đã có sẵn:**
- Mô hình JWT tại Gateway: Gateway verify JWT, inject `X-User-Id`/`X-User-Role` vào header, service downstream tin tưởng Gateway (không tự verify JWT lại).
- Vai trò: BUYER (mặc định), ADMIN, STAFF (được **ADMIN tạo tài khoản**, không tự đăng ký).
- SELLER: chưa làm ở giai đoạn này.
- **Mở, cần quyết định sau P6:** có làm tính năng OTP xác thực thiết bị mới (device verification OTP) hay không — quyết định phụ thuộc vào việc bot-detection ở P6 có cần tín hiệu "thiết bị đã tin cậy" hay không. Không chặn tiến độ P1, có thể bổ sung sau nếu cần.

**Việc cần làm:**
1. Entity `User` (id, email, password_hash, full_name, role, status, created_at, deleted_at).
2. API: đăng ký, xác thực email (OTP), đăng nhập, refresh token (**có xoay vòng**), quên mật khẩu.
3. OAuth2 Google login.
4. API tạo tài khoản STAFF (chỉ ADMIN gọi được).
5. Gateway: cấu hình filter verify JWT + inject header, strip `X-User-Id` từ client trước khi xử lý (chống header injection).

**Tài liệu cần sinh ra ở bước sau:**
- ERD `user_service`.
- API spec (OpenAPI) cho toàn bộ endpoint auth + user.
- Sequence diagram: đăng ký, đăng nhập kèm luồng JWT qua Gateway.

**Tiêu chí hoàn thành:** Luồng đăng ký → xác thực email → đăng nhập → gọi một API cần xác thực (qua Gateway, có header injection đúng) chạy thông suốt. ADMIN tạo được tài khoản STAFF.

---

### P2 — Product (Catalog: sự kiện, sơ đồ ghế, GA)

**Mục tiêu:** ADMIN tạo sự kiện với cả hai loại vé (ghế số + GA phẳng); BUYER xem được.

**Quyết định đã có sẵn:**
- Product chỉ đọc là chính, ghi ít (chỉ ADMIN thiết lập).
- Product **không xử lý giữ chỗ** — đó là việc của Order.
- GA là một kho số lượng phẳng (không chia khu vực con) ở giai đoạn này.
- Giá niêm yết bằng USD.

**Việc cần làm:**
1. Data model: `Event` (tên, địa điểm, thời gian diễn, thời gian mở/đóng bán, trạng thái), `Venue`, `SeatMap`/`Seat` (cho vé có số ghế), `TicketType` (loại SEATED hoặc GENERAL_ADMISSION, số lượng gốc, giá).
2. API ADMIN: tạo/sửa sự kiện, tạo sơ đồ ghế, tạo hạng vé (cả seat map và GA) cho một sự kiện.
3. API BUYER: danh sách sự kiện, chi tiết sự kiện, xem sơ đồ ghế + tình trạng (dữ liệu tĩnh từ Product, **chưa phản ánh hold thời gian thực** — việc đó Order xử lý riêng, cần làm rõ ở P3 cách hai bên đồng bộ tình trạng hiển thị).

**Tài liệu cần sinh ra ở bước sau:**
- ERD `product_service`.
- API spec catalog (ADMIN + BUYER).

**Tiêu chí hoàn thành:** ADMIN tạo được một sự kiện có cả sơ đồ ghế và hạng vé GA; BUYER xem được danh sách và chi tiết sự kiện đó.

---

### P3 — Order: Giỏ hàng, giữ chỗ, chống bán trùng vé

**Mục tiêu:** Đây là phần lõi xử lý đồng thời của toàn hệ thống — trọng tâm để chứng minh năng lực backend trong CV.

**Quyết định đã có sẵn:**
- Giỏ hàng trong phạm vi **một sự kiện** (nhiều hạng vé/số lượng trong một đơn).
- Redis TTL cho hold (lớp nhanh) + ràng buộc/update nguyên tử ở DB (lớp đảm bảo cuối) — xem mục 3.5.
- Idempotency key cho các thao tác dễ bị retry (đặc biệt là khởi tạo thanh toán, xem P4).
- Order → bot-detection là bất đồng bộ (Kafka) ở bước thêm giỏ hàng.

**Cần quyết định khi thiết kế chi tiết (backlog từ mục 2):**
- Ai thực thi thao tác trừ tồn kho: Order tự trừ, hay gọi API sang Product?

**Việc cần làm:**
1. Data model: `Order` (trạng thái: CART → PENDING_PAYMENT → PAID → TICKETS_ISSUED / CANCELLED / EXPIRED), `OrderItem` (seat_id hoặc ticket_type_id + số lượng), `Hold` (trạng thái HELD/EXPIRED/RELEASED/CONFIRMED, `expires_at`).
2. API: thêm/bỏ vé khỏi giỏ (tạo/hủy hold), xem giỏ hàng hiện tại, xác nhận giỏ hàng để chuyển sang bước thanh toán.
3. Cơ chế tự nhả hold hết hạn: kết hợp TTL của Redis + job định kỳ kiểm tra/dọn dẹp ở DB (lưới an toàn nếu Redis lỗi).
4. Publish sự kiện Kafka mỗi khi có hành động thêm giỏ hàng, để bot-detection (P6) tiêu thụ.
5. Kiểm thử tải bằng k6: mô phỏng nhiều request tranh chấp cùng một ghế, xác nhận không có ghế nào bị giữ/bán cho hai người.

**Tài liệu cần sinh ra ở bước sau:**
- ERD `order_service`.
- State machine đầy đủ của `Order` và `Hold`.
- API spec giỏ hàng/giữ chỗ.
- Kết quả kiểm thử tải (k6) làm bằng chứng chống bán trùng.

**Tiêu chí hoàn thành:** k6 xác nhận không có ghế nào bị bán trùng khi nhiều request tranh chấp đồng thời; hold hết hạn được nhả đúng.

---

### P4 — Payment: Tích hợp Stripe

**Mục tiêu:** Hoàn tất luồng tiền, sinh vé sau khi thanh toán thành công, xử lý hoàn tiền.

**Quyết định đã có sẵn:**
- Order → Payment: đồng bộ, chỉ để tạo phiên thanh toán (nhận lại link/`client_secret`).
- Stripe → Payment: webhook, bắt buộc idempotent (Stripe có thể gửi lại cùng webhook nhiều lần).
- Payment → Order: bất đồng bộ (Kafka), báo `payment.succeeded`/`payment.failed`.
- Order sinh QR/vé sau khi nhận tín hiệu thành công (không phải Payment sinh vé — xem lý do ở mục 2, "ai sở hữu đầy đủ thông tin").
- Chính sách hoàn vé: 3 mốc đã nêu ở FR4 (mục 1). **Cần chi tiết hóa đầy đủ khi viết tài liệu API**, bao gồm: trạng thái Order tương ứng ở mỗi mốc, số tiền hoàn cụ thể theo công thức `giá vé × % hoàn`, và cách hiển thị cho người dùng trước khi họ xác nhận hủy.
- Luồng hoàn tiền: Order tính % theo chính sách → Order gọi Payment thực hiện hoàn qua Stripe → Payment thực hiện và báo kết quả (đồng bộ hoặc bất đồng bộ tùy độ trễ thực tế của Stripe refund API — cần xác nhận khi thiết kế chi tiết) → Order cập nhật trạng thái vé/đơn hàng.
- Đồng tiền: USD (do giới hạn Stripe sandbox).

**Việc cần làm:**
1. Tích hợp Stripe SDK: tạo PaymentIntent/Checkout Session.
2. Endpoint nhận webhook: xác thực chữ ký Stripe, xử lý idempotent (kiểm tra event ID đã xử lý chưa trước khi xử lý tiếp).
3. Publish sự kiện Kafka `payment.succeeded`/`payment.failed` sau khi xử lý webhook.
4. API thực hiện hoàn tiền qua Stripe (Refund API), publish kết quả.
5. Order: consumer nhận sự kiện thanh toán → sinh vé + QR (nếu thành công) hoặc nhả hold (nếu thất bại); consumer nhận kết quả hoàn tiền → cập nhật trạng thái đơn hàng/vé.

**Tài liệu cần sinh ra ở bước sau:**
- ERD `payment_service`.
- Sequence diagram: luồng thanh toán đầy đủ (Order → Payment → Stripe → webhook → Order sinh vé) và luồng hoàn tiền đầy đủ (với bảng chi tiết % theo từng mốc).
- Event schema Kafka: `payment.succeeded`, `payment.failed`, `refund.completed`, `refund.failed`.

**Tiêu chí hoàn thành:** Luồng mua vé thành công/thất bại và luồng hoàn tiền (cả 3 mốc + trường hợp ban tổ chức hủy) chạy đúng bằng thẻ test của Stripe.

---

### P5 — Notification & vòng đời vé (check-in)

**Mục tiêu:** Thông báo cho người dùng qua email/WebSocket; xử lý quét vé lúc vào cổng.

**Quyết định đã có sẵn:**
- Tên bảng: `notifications`.
- Hỗ trợ cả hai kênh: email và WebSocket (real-time trên giao diện).
- Check-in/quét vé đặt trong **Order** (vì Order sở hữu vé).
- Chỉ **ADMIN và STAFF** được quét vé (STAFF do ADMIN tạo tài khoản ở P1).

**Việc cần làm:**
1. Notification: consumer Kafka nhận sự kiện từ Order/Payment (vé mới, hủy vé, cảnh báo bot), lưu bảng `notifications`, gửi email, đẩy real-time qua WebSocket (STOMP hoặc tương đương).
2. Order: API quét mã vé (nhận mã QR, trả về hợp lệ/đã dùng/không tồn tại), cập nhật trạng thái vé sang CHECKED_IN, ghi nhận thời điểm quét.
3. Phát hiện quét trùng: nếu vé đã ở trạng thái CHECKED_IN, từ chối lần quét sau và trả về thông tin lần quét trước (ai quét, lúc nào) để STAFF xử lý tại chỗ.
4. Phân quyền endpoint quét vé: chỉ ADMIN/STAFF.

**Tài liệu cần sinh ra ở bước sau:**
- ERD `notification_service`.
- API spec: notification (lấy danh sách, đánh dấu đã đọc) + check-in.
- Sequence diagram: luồng thông báo end-to-end, luồng quét vé.

**Tiêu chí hoàn thành:** Người dùng nhận được thông báo đúng lúc qua cả email và WebSocket; quét vé lần hai bị từ chối và báo rõ lý do.

---

### P6 — Bot Detection (ML không giám sát)

**Mục tiêu:** Mô hình Isolation Forest phát hiện hành vi bất thường ở bước thêm giỏ hàng, dựa trên dữ liệu tự tạo (không dùng dataset gian lận có sẵn vì không có dataset nào khớp với hành vi mua vé thực tế).

**Quyết định đã có sẵn:**
- Không có dataset công khai nào phù hợp (đã khảo sát: Web Server Access Logs không có nhãn đúng loại bot; Twitter Bot Detection, IoT Botnet, Network Intrusion đều lệch tầng dữ liệu). Phải tự sinh dữ liệu.
- Nguồn dữ liệu: **script mô phỏng bot bằng k6/JMeter** (dùng lại chính công cụ kiểm thử tải của P3) + traffic người dùng thật (tự thao tác, dùng thử).
- Kết quả rủi ro cao → **chặn các lần thêm giỏ hàng tiếp theo từ cùng thiết bị + đánh dấu để review**, **không hủy hold hiện tại đã tạo** (tránh phạt oan người dùng thật nếu model sai).
- Giao tiếp: Order → bot-detection qua Kafka (bất đồng bộ), bot-detection publish điểm rủi ro ngược lại.

**Việc cần làm:**
1. Viết script k6 mô phỏng hành vi bot: tốc độ request cao, không có độ trễ tự nhiên, không xem chi tiết sự kiện trước khi thêm giỏ hàng.
2. Thu thập log hành vi thật (tự thao tác chậm rãi, có xem trang trước khi mua) làm mẫu đối chứng.
3. Trích xuất feature: tốc độ thêm giỏ hàng (số request/phút), thời gian giữa các request, số tài khoản trên cùng một device fingerprint (hash), tuổi tài khoản.
4. Huấn luyện Isolation Forest (scikit-learn), đánh giá bằng cách so sánh với "nhãn" tự biết (vì chính mình biết script nào là bot).
5. Đóng gói phục vụ qua FastAPI, consumer Kafka nhận sự kiện `cart.item_added` (hoặc tên tương đương từ P3), publish điểm rủi ro.
6. Order: xử lý kết quả theo quy tắc đã chốt ở trên.

**Ràng buộc dữ liệu cá nhân (liên kết với P8):** chỉ dùng **hash** của device fingerprint, không lưu IP thô nếu không cần thiết, không cần thông tin định danh thật (tên, email) trong feature.

**Tài liệu cần sinh ra ở bước sau:**
- Báo cáo notebook: EDA, feature engineering, kết quả đánh giá mô hình.
- Event schema Kafka: `cart.item_added` (input), `bot_score.evaluated` (output).
- API spec service bot-detection.

**Tiêu chí hoàn thành:** Traffic bot tự tạo (từ script k6) bị gắn cờ với độ chính xác chấp nhận được trên chính dữ liệu tự tạo; có báo cáo đánh giá rõ ràng, trung thực về giới hạn của cách làm này (dữ liệu tự tạo, không phải dữ liệu thật từ production).

---

### P7 — AI Assistant (LLM Q&A)

**Mục tiêu:** Trợ lý hỏi đáp cho người mua, dùng LLM API có sẵn (không tự huấn luyện).

**Quyết định đã có sẵn:**
- Service riêng, stateless.
- Phạm vi câu hỏi: thông tin sự kiện, chính sách hoàn/hủy vé, trạng thái đơn hàng của **chính người đang hỏi** (không được trả lời thông tin của người khác).
- Cần **chuẩn bị trước** thông tin sự kiện, chính sách hoàn/hủy, và tài liệu liên quan làm nguồn ngữ cảnh (grounding) cho LLM, để tránh trả lời sai/bịa (hallucination).

**Việc cần làm:**
1. Chuẩn bị "knowledge base": văn bản chính sách hoàn/hủy (từ FR4), câu hỏi thường gặp, mô tả cách hệ thống hoạt động — dùng làm system prompt hoặc nguồn cho retrieval.
2. API nhận câu hỏi từ người dùng đã đăng nhập (qua Gateway, có `X-User-Id`).
3. Lấy ngữ cảnh động: gọi API sang Order (đơn hàng của chính user đó) và Product (thông tin sự kiện liên quan) trước khi gọi LLM.
4. Ghép ngữ cảnh tĩnh (chính sách) + ngữ cảnh động (đơn hàng cụ thể) thành prompt, gọi LLM API, trả lời.
5. Kiểm tra: đảm bảo không rò rỉ dữ liệu của người dùng khác qua bất kỳ câu hỏi nào.

**Tài liệu cần sinh ra ở bước sau:**
- Nội dung knowledge base (chính sách, FAQ).
- API spec.
- Mô tả luồng lấy ngữ cảnh + cấu trúc prompt.

**Tiêu chí hoàn thành:** Trả lời đúng cho các câu hỏi mẫu về chính sách và đơn hàng của chính người dùng; từ chối hoặc không có khả năng trả lời về đơn hàng của người khác.

---

### P8 — Bảo mật dữ liệu cá nhân

**Mục tiêu:** Áp dụng nguyên tắc bảo vệ dữ liệu cá nhân đúng phạm vi thực tế của hệ thống.

**Phạm vi dữ liệu liên quan:**
- Device fingerprint (dùng cho bot-detection ở P6).
- Địa chỉ IP (nếu có thu thập).
- Dữ liệu hành vi thao tác (tốc độ thêm giỏ hàng, số lượng) — gắn với `user_id` thì trở thành hồ sơ hành vi cá nhân.
- **Không** bao gồm: thông tin thẻ thanh toán (Stripe xử lý, hệ thống chỉ nhận `payment_intent_id`/trạng thái), không bao gồm dữ liệu vị trí chi tiết.

**Quyết định đã có sẵn:**
1. **Consent**: xin sự đồng ý khi thu thập device fingerprint, phạm vi rõ ràng là "cho mục đích phát hiện hành vi bất thường lúc mua vé" (không dùng cho mục đích khác).
2. **Ẩn danh hóa**: chỉ lưu **hash** của device fingerprint, không lưu IP thô nếu không thực sự cần thiết cho tính năng nào khác.
3. **Retention**: dữ liệu hành vi dùng để huấn luyện có thời hạn lưu trữ xác định, không giữ vô thời hạn.

**Việc cần làm:**
1. Viết checklist bảo mật/dữ liệu cá nhân đúng phạm vi trên.
2. Thêm bước xin consent ở giao diện (lần đầu người dùng thao tác trên hệ thống, hoặc lần đầu dữ liệu thiết bị được thu thập).
3. Đảm bảo pipeline dữ liệu huấn luyện ở P6 chỉ dùng dữ liệu đã hash/ẩn danh.
4. Thiết lập job/quy tắc xóa dữ liệu hành vi quá hạn lưu trữ.

**Tài liệu cần sinh ra ở bước sau:**
- `legal_compliance.md`.

**Tiêu chí hoàn thành:** Checklist bảo mật khớp đúng với dữ liệu thực tế hệ thống thu thập (không thừa, không thiếu so với những gì đã liệt kê ở trên).

---

### P9 — Hoàn thiện: bảo mật hạ tầng, CI/CD, phát hành

**Mục tiêu:** Đưa dự án về trạng thái có thể trình bày trong CV: chạy được, có tài liệu, deploy thật.

**Việc cần làm:**
1. Rate limiting ở API Gateway (theo user/IP).
2. CI/CD đầy đủ: build, test (bao gồm Testcontainers cho test tích hợp với PostgreSQL/Kafka/Redis thật), build Docker image, deploy.
3. Deploy VPS qua Docker Compose (production).
4. README tổng hợp, hướng dẫn chạy dev, tài liệu API (OpenAPI) cho toàn bộ 8 service.
5. Viết mục "Giới hạn" trong README: nêu rõ dữ liệu ML là tự tạo (không phải production thật), thanh toán là Stripe test/sandbox bằng USD, các phạm vi chưa làm (SELLER, bán lại vé...).

**Tiêu chí hoàn thành:** Một lệnh chạy được toàn bộ hệ thống từ đầu; có tài liệu API đầy đủ; deploy thật lên VPS và truy cập được.

---

## 5. Ghi chú tổng hợp thảo luận (để tránh lặp lại các quyết định đã chốt)

Các quyết định dưới đây **đã chốt qua nhiều vòng thảo luận** và không cần bàn lại trừ khi có lý do mới phát sinh khi code thật:

- Payment không bao giờ biết chi tiết vé — chỉ biết tiền. Nếu sau này có nhu cầu Payment cần biết thêm gì, đó là dấu hiệu cần xem lại ranh giới, không phải điều hiển nhiên chấp nhận.
- Idempotency key và ràng buộc DB chống tranh chấp ghế là **hai cơ chế khác nhau**, giải quyết hai vấn đề khác nhau, không thay thế nhau.
- Async không "tốn tài nguyên hơn" sync — thường ngược lại, vì sync giữ thread/connection trong lúc chờ.
- Model bot-detection không cần dữ liệu định danh thật (tên, email) — chỉ cần đặc trưng hành vi và hash thiết bị.
- Mọi thay đổi ranh giới service trong tương lai (nếu có) là bình thường trong quá trình code thật.

---

*Hết tài liệu v1. Khi bắt đầu thiết kế chi tiết một phase cụ thể (API design, ERD, sequence diagram), chỉ cần cung cấp lại đúng phần mô tả phase đó từ tài liệu này.*
