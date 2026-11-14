# CLAUDE.md — Vantix

Hướng dẫn cho Claude khi làm việc trong repo này (Claude Code / Cowork). Đọc kỹ trước khi sửa bất kỳ file nào.

## 1. Dự án

Vantix là nền tảng bán vé sự kiện (vé ghế số SEATED + vé đứng GA), chống bán trùng vé khi đồng thời cao, bot-detection (Isolation Forest, dữ liệu tự tạo), trợ lý AI (LLM API), thanh toán Stripe test (USD). Monorepo, 8 service.

```
/
├── backend/                 # Maven multi-module (groupId com.dev, artifact vantix)
│   ├── common-lib/          # commonlib-api-response | -security | -kafka | -jwt
│   └── services/            # api-gateway, user, product, order, payment, notification (Java)
│                            # bot-detection, assistant (Python/FastAPI, ngoài Maven reactor)
├── frontend/                # Next.js (App Router) — có AGENTS.md riêng
├── docs/                    # Tài liệu thiết kế, xem mục 8
└── docker-compose.yml       # Hạ tầng dev: Postgres, Redis, Kafka (KRaft), pgAdmin, Kafka UI, RedisInsight
```

## 2. Stack (đọc `pom.xml` / `package.json` là nguồn sự thật)

- Backend: Java 21, **Spring Boot 4.1.x**, Spring Cloud 2025.1.2, Lombok 1.18.42, Maven (mỗi module có `mvnw`).
- **Boot 4 khác 3.x**: không viết API theo trí nhớ khi chưa chắc. Ví dụ đã gặp trong repo:
    - Jackson 3: `tools.jackson.databind.json.JsonMapper` (annotation vẫn là `com.fasterxml.jackson.annotation.*`).
    - Kafka serializer: `JacksonJsonSerializer`.
    - Gateway: `spring-cloud-starter-gateway-server-webflux`, config dưới `spring.cloud.gateway.server.webflux`.
    - Flyway: `spring-boot-starter-flyway` + `flyway-database-postgresql`.
- DB: PostgreSQL + Flyway (DB riêng mỗi service), Redis, Kafka.
- Frontend: Next 16, React 19, TypeScript, Tailwind 4, shadcn (base-nova, `@base-ui/react`), TanStack Query, Zustand, React Hook Form + Zod, Axios, date-fns. `cn` import từ package `"cn"` hoặc `@/lib/utils`.

## 3. Service, port, DB

| Service | Port | DB (theo `application.yaml`) |
|---|---|---|
| api-gateway | 8999 | — |
| notification | 9000 | `notifications_service` |
| order | 9001 | `orders_service` |
| payment | 9002 | `payments_service` |
| product | 9003 | `products_service` |
| user | 9004 | `users_service` |
| bot-detection | 9005 | — |
| assistant | 9006 | — |

- Service Java có `context-path: /api` → controller mapping **không** thêm `/api` (ví dụ `@RequestMapping("/events")`).
- Tên DB trong `application.yaml` là nguồn sự thật (một số doc cũ ghi tên số ít, sai). `docker-compose` tạo 5 DB qua `docker/postgres/init/01-create-databases.sql` **chỉ khi volume Postgres còn trống**; volume cũ chạy tay script (README), nếu không Flyway lỗi.
- Cổng dev phụ: pgAdmin 8081, Kafka UI 8085, RedisInsight 5540.

## 4. Convention backend

**Cấu trúc & code**
- Package theo layer. Theo code hiện có: `controller`, `services` (interface + `*Impl`), `repository`, `entities`, `dto/request`, `dto/response`, `exception`, `config`, `security`. Module mới theo đúng tên package của service đang sửa.
- Mapping Entity ↔ DTO viết tay (builder), **không** MapStruct.
- Lombok cho DTO. JPA entity **không** `@Data`; dùng `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` + `@ToString(onlyExplicitlyIncluded = true)`.
- Comment tiếng Việt, giải thích "tại sao", không comment dòng hiển nhiên.

**Response & lỗi**
- `ApiResponse<T>` `{code=200 mặc định, message (NON_NULL), result}`; HTTP status 201 vẫn trả `code: 200`.
- Lỗi: enum `ErrorCode` (code, message, HttpStatus) + `AppException(ErrorCode[, data])` + `GlobalExceptionHandler extends BaseGlobalExceptionHandler`. Không xử lý lỗi rời rạc trong controller.
- Dải errorCode: 1xxx Common, 2xxx user, 3xxx product, 4xxx order, 5xxx payment, 6xxx notification, 7xxx bot-detection, 8xxx assistant, 9xxx gateway. Trong dải chia nhóm theo hàng trăm.
- Lỗi phân quyền: dùng `@PreAuthorize("hasRole('ADMIN')")` ở method (cần `@EnableMethodSecurity`), **không** dùng rule URL `hasRole` — lỗi 403 từ filter chain không đi qua `GlobalExceptionHandler`, sai format JSON.

**Auth (Trust-The-Gateway)**
- Gateway verify JWT, **luôn xóa** `X-User-Id`/`X-User-Role` do client gửi rồi inject lại. Service phía sau dùng `GatewayHeaderAuthFilter` (`commonlib-security`), **không** tự verify JWT.
- Thêm API công khai mới ⇒ phải thêm vào `app.gateway.public-endpoints` (dạng `METHOD /path`) ở `api-gateway/application.yaml` **và** `permitAll` ở `SecurityConfig` của service. Thêm prefix route mới ⇒ cập nhật `routes`.
- Access token chỉ chấp nhận `token_type=access`; `mcp=true` chỉ được gọi API trong `password-change-allowlist`.

**Transaction & Kafka**
- user-service: dùng `TransactionTemplate` bao riêng từng lệnh ghi (có pattern "ghi rồi mới ném lỗi"). product-service: `@Transactional` thường. Chọn theo service đang sửa.
- Publish Kafka không được làm hỏng transaction nghiệp vụ. user: gửi sau `transactionTemplate.execute`, lỗi chỉ log (chấp nhận mất event). product (`event_outbox`) và order (`order_event_outbox`): **transactional outbox** — ghi cùng transaction, scheduler gửi + retry, consumer phải idempotent. Key = id của aggregate.
- Hằng số topic ở `KafkaTopics`, payload là `record` trong `commonlib-kafka/event`. Chỉ thêm topic khi có code dùng.

**DB**
- Flyway `V{n}__{desc}.sql`, **không sửa migration đã áp dụng**, `ddl-auto: validate`. Mọi thay đổi schema = migration mới.
- Ràng buộc DB là lớp chặn cuối (unique index, partial unique index, update nguyên tử); check ở Java chỉ để báo lỗi sớm.

**Common-lib**: chỉ tách vào khi ≥ 3 service cần (rule of 3). Mỗi artifact nhỏ, riêng dependency.

## 5. Quyết định kiến trúc đã chốt (không bàn lại trừ khi có lý do mới)

- Payment không biết chi tiết vé; Order sở hữu đơn, hold, vé, check-in, tính % hoàn.
- Chống bán trùng cần **đủ cả ba**: idempotency key (retry cùng client) + Redis TTL (hold nhanh) + ràng buộc/update nguyên tử ở DB Order (lớp cuối).
- Order → Payment: sync REST. Stripe → Payment webhook: idempotent. Payment → Order, Order → bot-detection, Order → Notification: Kafka.
- Bot rủi ro cao: chặn lần thêm giỏ kế tiếp + đánh dấu review, **không** hủy hold hiện tại.
- Hoàn vé: ≥ 7 ngày 100%, 24h–7 ngày 50%, < 24h 0%, ADMIN hủy sự kiện 100%.
- Vai trò: BUYER, ADMIN, STAFF (ADMIN tạo). SELLER để sau.
- P3 Order: một Order = một event; mỗi buyer tối đa một CART/event; hold chung deadline 10 phút từ hold đầu, thêm item không gia hạn; tạo hold all-or-nothing.

## 6. Bảo mật & dữ liệu nhạy cảm

- Không log/commit: mật khẩu, mật khẩu tạm, OTP, refresh token, payload Kafka `user.staff-account-created`.
- Secret (`JWT_SECRET_KEY`, `OTP_HMAC_SECRET`, Google client, DB password…) lấy từ biến môi trường / `.env`. **Không** đọc/in/commit `.env`.
- Refresh token đi bằng cookie `httpOnly` `Path=/api/auth`; access token chỉ giữ trong bộ nhớ ở frontend (không `localStorage`).
- Khi gặp code đang log secret/PII (message exception có thể chứa giá trị dữ liệu hoặc token): báo lại, không tự lan rộng. Chỉ log loại lỗi/mã lỗi và id nội bộ.

## 7. Frontend

**Bắt buộc đọc `frontend/AGENTS.md`** (đã được `frontend/CLAUDE.md` import). Điểm chính:
- Next.js bản mới có breaking change: đọc `node_modules/next/dist/docs/` trước khi viết code liên quan routing/API của Next.
- Giữ cấu trúc: `app/(auth)|(main)|(admin)`, `components/{ui,auth,events,errors}`, `lib/api`, `store`, `types`. Không tự refactor ngoài phạm vi.
- Hỏi trước khi thêm/nâng dependency. Dùng `npm` + `package-lock.json`.
- API client (`lib/api/client.ts`): refresh **serialize** (một request refresh duy nhất — gọi trùng sẽ bị reuse detection đá khỏi mọi thiết bị). Chỉ tự refresh khi `9003`; `9001/9002` xóa session. `1004` → `/403`, `1000`/5xx → `/500`.
- Hiển thị lỗi bằng `error.response.data.message` (`getErrorMessage`); không tự dịch bảng errorCode.
- Zod schema phản ánh đúng validation backend. Validate client không thay validate server.
- API chưa có ⇒ mock đặt ở `lib/mocks/`, có type, đánh dấu rõ; không nhúng vào component, không coi là contract thật. UI P3 không được giả lập payment.
- UI có thiết kế UXMagic: đọc qua MCP trước khi code. Không truy cập được thì nói rõ, không tuyên bố đã đối chiếu.
- UI text hiện dùng tiếng Anh; ẩn/hiện theo role chỉ là UX, phân quyền thật ở backend.
- Kiểm tra: `npm run lint`, typecheck; `npm run build` (script dùng `--webpack`) khi đổi routing/config/dependency.

## 8. Tài liệu (`docs/`) — nguồn sự thật cho thiết kế

| Đường dẫn | Nội dung |
|---|---|
| `docs/CONTEXT.md` | Tóm tắt ngắn để dán cho AI. **Đang lệch**: còn ghi OTP ở bảng `USER_OTP` (thực tế đã chuyển sang Redis) — cập nhật khi có dịp |
| `docs/00_roadmap_phases_v1.md` | Roadmap P0–P9, FR, service boundaries |
| `docs/P0_infrastructure_summary.md`, `docs/P0_frontend_setup.md` | Hạ tầng, setup frontend |
| `docs/auth/` | `P1_auth_design.md`, `P1_auth_api_design.md` (API đã khớp code, **mới hơn** design) |
| `docs/product/` | `P2_product_design.md`, `P2_product_api_design.md`, `P2_product_ui_plan.md`, `P1_auth_ui_plan.md` |
| `docs/order/` | `P3_order_design.md`, `P3_order_api_design.md`, `P3_order_ui_plan.md` |
| `README.md`, `ARCHITECTURE.md`, `DECISIONS.md`, `PROGRESS.md`, `TASKS.md` (gốc repo) | Tổng quan, kiến trúc, quyết định, tiến độ, backlog — cập nhật khi đổi trạng thái phase |

Quy ước mỗi phase: `Pn_*_design.md` (quyết định, ERD, sequence) → `Pn_*_api_design.md` (hợp đồng API, errorCode, bước thực thi) → `Pn_*_ui_plan.md` (frontend). **Khi code lệch tài liệu, sửa tài liệu trong cùng thay đổi**; khi tài liệu lệch code đang chạy thì code là sự thật, báo lại để cập nhật doc.

## 9. Tiến độ

P0 hạ tầng ✅ · P1 User & Auth ✅ (14 API) · P2 Product ✅ (có integration test) · P3 Order 🟡 đã code + test concurrency/integration (9 API, hold Redis + DB, outbox có dead-letter, consumer `product.event-cancelled`, UI cart; còn: E2E xuyên service + kết quả k6) · P4–P9 chưa bắt đầu. Integration test dùng Postgres/Redis thật (D21: compose local, service container ở CI, DB `*_test` tạo bằng `docker/postgres/test/create-test-databases.sql`). Chi tiết: `PROGRESS.md`, backlog: `TASKS.md`.

## 10. Cách làm việc với Claude

- Trước khi sửa: nêu phase + file liên quan, đọc code hiện có và doc tương ứng. Thiếu ngữ cảnh thì hỏi, không đoán.
- Việc lớn/chưa rõ: đưa plan ngắn trước, sau đó code hoàn chỉnh theo convention trên, nêu rõ giả định chưa chắc.
- Chỉ sửa trong phạm vi yêu cầu; không refactor, không đổi format file không liên quan, không sửa `mvnw*`/`.mvn`.
- Code mới phải biên dịch được: kiểm tra import, null-safety, transaction, edge case. Backend chạy `./mvnw -q -pl <module> -am verify` (hoặc `mvn clean verify` ở `backend/`); báo rõ phần chưa chạy được.
- Kết thúc bằng tóm tắt ngắn: đã đổi gì, đã kiểm tra gì, còn hạn chế gì, doc nào cần cập nhật.
- Thao tác khó đảo ngược (xóa file, sửa migration đã áp dụng, đổi schema Kafka đã dùng, đổi secret/config deploy) → hỏi trước.