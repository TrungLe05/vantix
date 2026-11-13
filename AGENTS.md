# Vantix — hướng dẫn cho AI agent

Nền tảng bán vé sự kiện (seat map + GA), có bot-detection (ML) và trợ lý AI. Monorepo Maven, mỗi service là một module.
Mọi agent (Codex, Antigravity, Claude...) đều tuân theo file này. Nếu file này mâu thuẫn với `docs/`, DỪNG và hỏi, không tự chọn bên nào.

## Tech stack (đã chốt, KHÔNG tự ý đổi version)
- Java 21, Spring Boot 4.1.x, Spring Cloud 2025.1.2, Maven, Lombok 1.18.42.
- Gateway: `spring-cloud-starter-gateway-server-webflux` (KHÔNG dùng `spring-cloud-starter-gateway`, artifact cũ đã bị xóa).
- PostgreSQL + Flyway (database-per-service), Redis, Kafka (KRaft), Stripe (test/sandbox, USD).
- Service Python (FastAPI) ngoài Maven reactor: `bot-detection-service`, `assistant-service`.
- Spring Boot 4 / Security 7 / Jackson có thể khác bản 3.x trong trí nhớ của model: kiểm tra tài liệu chính thức hoặc code hiện có trước khi dùng API. Không chắc thì nói rõ, không đoán.

## Cấu trúc repo
```
backend/
  common-lib/{commonlib-api-response, commonlib-security, commonlib-kafka, commonlib-jwt}
  services/{api-gateway, user-service, product-service, order-service,
            payment-service, notification-service, bot-detection-service, assistant-service}
frontend/  ← Next.js, có AGENTS.md riêng
docs/      ← tài liệu thiết kế theo phase (nguồn sự thật)
```
| Service | Port | Database |
|---|---|---|
| api-gateway | 8999 | — |
| notification | 9000 | notifications_service |
| order | 9001 | orders_service |
| payment | 9002 | payments_service |
| product | 9003 | products_service |
| user | 9004 | users_service |
| bot-detection (Python) | 9005 | — |
| assistant (Python) | 9006 | — |

Tên DB lấy theo `application.yaml` của từng service. Compose không tự tạo DB → tạo trước khi chạy service, nếu không Flyway lỗi.

Mọi service Java có `server.servlet.context-path: /api` → `@RequestMapping` KHÔNG thêm tiền tố `/api` (vd `/auth/login`, không phải `/api/auth/login`).

## Lệnh
- Hạ tầng dev: `docker compose up -d` (biến môi trường mẫu: `.env.example`, `frontend/.env.example`)
- Build + test toàn bộ (trong `backend/`): `mvn --batch-mode clean verify`
- Chạy 1 module (trong `backend/`): `mvn -pl services/user-service -am spring-boot:run`

## Convention backend (BẮT BUỘC)
- Package theo layer, theo đúng tên package của service đang sửa: user/product dùng `controller`, `services` (interface + `*Impl`), `repository`, `entities`, `dto/request|response`, `exception`, `config`, `security`; order dùng `service`, `entity`, `enums`, `client`, `event`.
- Mapping Entity <-> DTO viết tay trong service/mapper thuần. KHÔNG dùng MapStruct hay thư viện mapper khác.
- DTO: ưu tiên class + Lombok; record chỉ khi phù hợp và nhất quán với code xung quanh.
- JPA entity: KHÔNG dùng `@Data`. Cân nhắc kỹ `@EqualsAndHashCode`/`@ToString` (tránh lazy loading, vòng lặp).
- Response luôn là `ApiResponse<T>` (`commonlib-api-response`): `code` (mặc định 200), `message` (`@JsonInclude(NON_NULL)`), `result`. Lỗi cũng theo format này.
- Lỗi: mỗi service có enum `...ErrorCode implements ErrorCode` gồm `code`, `message`, `HttpStatus`. Ném exception nghiệp vụ mang `ErrorCode`; xử lý tập trung ở `GlobalExceptionHandler` (`@ControllerAdvice`). KHÔNG try/catch trả lỗi rời rạc trong controller.
- Flyway: file `V{version}__{description}.sql` trong `db/migration`. KHÔNG sửa migration đã áp dụng; mọi thay đổi schema qua migration mới. `spring.jpa.hibernate.ddl-auto: validate`.
- Auth: Gateway verify JWT và inject `X-User-Id`/`X-User-Role` (strip header này từ client trước). Service downstream dùng `GatewayHeaderAuthFilter` (`commonlib-security`), KHÔNG tự verify JWT.
- Giao tiếp: sync (REST/OpenFeign) chỉ khi cần phản hồi ngắn ngay; async (Kafka) khi chờ hệ thống ngoài hoặc không cần chờ. Consumer Kafka phải idempotent.
- Common-lib: chỉ đưa code vào khi >= 3 service cùng cần. Dependency dùng >= 2 module phải lên `dependencyManagement` của root; dependency riêng một module có thể để version tại module, trừ khi yêu cầu dự án chỉ định quản lý tập trung ở parent.
- `spring-boot-maven-plugin` chỉ nằm trong `pluginManagement` ở root, KHÔNG áp lên module `commonlib-*`.
- Tận dụng Java 21 (record, switch expression, pattern matching) khi tự nhiên, không dùng để khoe.

## Ranh giới service (đã chốt)
- Payment chỉ biết tiền, KHÔNG biết chi tiết vé. Order sở hữu đơn hàng, giỏ, hold, vé/QR, check-in, tính % hoàn.
- Product chỉ đọc là chính, không xử lý hold.
- Chống bán trùng ghế cần CẢ BA lớp: idempotency key (khởi tạo thanh toán), Redis TTL (hold nhanh), ràng buộc/update nguyên tử ở DB Order (lớp cuối). Không thay thế lẫn nhau.
- Order -> bot-detection qua Kafka; rủi ro cao thì chặn lần thêm giỏ tiếp theo + đánh dấu review, KHÔNG hủy hold hiện tại.
- Gateway và assistant stateless, không có DB.

## KHÔNG được làm
- Không log hoặc in payload chứa mật khẩu tạm, OTP, token (đặc biệt event `user.staff-account-created`).
- Không lưu token thô: refresh token chỉ lưu SHA-256 hash.
- Không lưu IP thô hay thông tin định danh vào feature của bot-detection; chỉ hash device fingerprint.
- Không commit `.env`; chỉ cập nhật `.env.example`.
- Không thêm dependency mới, không đổi ranh giới service, không đổi quyết định đã chốt trong `docs/` khi chưa hỏi.
- Không viết lại toàn bộ file khi chỉ cần sửa một phần; không refactor ngoài phạm vi task.

## Quy trình làm việc
- Trước khi sửa: đọc file liên quan và `docs/` của phase đang làm. Thiếu ngữ cảnh thì hỏi, không đoán.
- Task nhỏ: đưa plan ngắn, chờ duyệt rồi mới sửa. Mỗi task một branch, commit nhỏ.
- Code mới phải có test (JUnit 5 + Mockito; integration test dùng Testcontainers khi cần DB/Kafka/Redis thật).
- Sau khi sửa chạy `mvn --batch-mode clean verify`; fail thì tự sửa, không báo xong.
- Quyết định phát sinh khi code (khác docs) phải cập nhật vào `docs/` cùng commit.
- Comment bằng tiếng Việt, giải thích "tại sao", không lặp lại "code làm gì".

## Phase hiện tại
- P0 hạ tầng, P1 User & Auth (`docs/auth/`), P2 Product (`docs/product/`): xong phần API, còn thiếu test.
- P3 Order (`docs/order/`): đã code, còn chạy E2E + k6 và test concurrency.
- P4–P9 chưa bắt đầu. Roadmap: `docs/00_roadmap_phases_v1.md`; tiến độ chi tiết: `PROGRESS.md`; backlog: `TASKS.md`.
- Việc treo: OTP xác thực thiết bị mới (quyết sau P6).

## Code mẫu chuẩn (golden example)
- Endpoint CRUD có phân quyền ADMIN, `@Transactional`, mapping tay, errorCode riêng: product-service — `controller/EventController.java`, `services/EventServiceImpl.java`, `exception/ProductErrorCode.java`, `db/migration/V1__create_product_tables.sql`.
- Luồng bảo mật dùng `TransactionTemplate`, Kafka sau commit: user-service — `controller/AuthController.java`, `services/AuthServiceImpl.java`.
- Luồng giao dịch đồng thời (idempotency, Redis + ràng buộc DB, outbox): order-service — `service/OrderServiceImpl.java`, `service/OrderCommandService.java`, `event/OrderOutboxScheduler.java`.
- Chưa có mẫu test chuẩn (mới có `OrderRequestHasherTest`).
