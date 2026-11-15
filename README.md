# Vantix

Nền tảng bán vé sự kiện (concert, hội thảo, thể thao…) hỗ trợ **vé ghế có số (SEATED)** và **vé đứng (GA)**. Trọng tâm là backend microservice xử lý đồng thời cao: chống bán trùng vé khi mở bán, phát hiện hành vi mua vé bất thường (bot) bằng ML không giám sát, trợ lý hỏi đáp dùng LLM và thanh toán Stripe ở chế độ test.

> Đây là dự án cá nhân để học và làm portfolio, không phải sản phẩm production: thanh toán là Stripe sandbox (USD), dữ liệu huấn luyện bot-detection là dữ liệu tự tạo.

## Mục tiêu

| # | Mục tiêu | Cách đạt |
|---|---|---|
| 1 | **Không bán trùng vé** khi nhiều người tranh cùng ghế/cùng kho GA | Ba lớp cùng lúc: idempotency key, hold Redis có TTL, ràng buộc/update nguyên tử ở PostgreSQL của Order |
| 2 | Giỏ hàng theo sự kiện, giữ chỗ có thời hạn | Một Order = một event; hold chung 10 phút; tạo hold all-or-nothing |
| 3 | Thanh toán an toàn | Stripe Checkout/PaymentIntent, webhook idempotent, Payment không biết chi tiết vé |
| 4 | Phát hiện bot ở bước thêm giỏ | Isolation Forest (Python) + luật tay, nhận `cart.item_added` qua Kafka; chặn khi rủi ro cao kèm luật hoặc lặp lại, ADMIN review |
| 5 | Trợ lý hỏi đáp | LLM API, chỉ trả lời về sự kiện, chính sách và đơn hàng của chính người hỏi |
| 6 | Check-in, thông báo | Quét QR chống quét trùng (ADMIN/STAFF), chỉ mở quanh giờ diễn; email + WebSocket |
| 7 | Bảo vệ dữ liệu cá nhân | Device ID ngẫu nhiên, chỉ lưu HMAC; consent và retention ở P8 |

Vai trò: **BUYER** (tự đăng ký), **ADMIN**, **STAFF** (ADMIN tạo). SELLER, bán lại vé, GA theo khu vực để giai đoạn sau.

Chính sách hoàn vé: ≥ 7 ngày trước giờ diễn hoàn 100%, 24h–7 ngày 50%, < 24h 0%; ADMIN hủy sự kiện hoàn 100%. Đơn có vé đã check-in chỉ hoàn phần vé chưa dùng.

## Trạng thái

P0 hạ tầng ✅ · P1 User & Auth ✅ · P2 Product ✅ · P3 Order ✅ · **P4 Payment 🟡** và **P5 Notification & check-in 🟡** đã code + integration test, chờ chạy end-to-end (Stripe test, Kafka, SMTP) · **P6 Bot-detection 🟡** đã code, huấn luyện trên dữ liệu k6 tự tạo, có báo cáo và chính sách chặn D46 · P7–P9 chưa bắt đầu. Test: backend 181, bot-detection 18 (pytest), Playwright cho P1–P3. Xem [PROGRESS.md](PROGRESS.md) và [TASKS.md](TASKS.md).

## Stack

- **Backend:** Java 21, Spring Boot 4.1, Spring Cloud 2025.1.2 (Gateway WebFlux, OpenFeign), Spring Security, JPA + Flyway, Lombok. Maven multi-module.
- **Hạ tầng:** PostgreSQL 14 (database-per-service), Redis, Kafka 4 (KRaft), pgAdmin, Kafka UI, RedisInsight — `docker-compose.yml`.
- **Python:** FastAPI cho `bot-detection-service` (scikit-learn Isolation Forest, aiokafka, Redis) và `assistant-service` (mới có `/health`).
- **Frontend:** Next.js 16 (App Router), React 19, TypeScript, Tailwind 4, shadcn (base-ui), TanStack Query, Zustand, React Hook Form + Zod, Axios.

## Cấu trúc repo

```
backend/
  common-lib/   commonlib-api-response | commonlib-security | commonlib-kafka | commonlib-jwt
  services/     api-gateway, user-service, product-service, order-service,
                payment-service, notification-service        (Java, Maven reactor)
                bot-detection-service, assistant-service      (Python, ngoài reactor)
frontend/       Next.js app (src/app/(auth)|(main)|(admin))
docs/           Tài liệu thiết kế theo phase — nguồn sự thật cho thiết kế
```

## Chạy môi trường dev

1. Sao chép `.env.example` thành `.env` và điền giá trị (không commit `.env`). Các biến service đang đọc: `POSTGRES_USER`, `POSTGRES_PASSWORD`, `KAFKA_CLUSTER_ID`, `JWT_SECRET_KEY`, `OTP_HMAC_SECRET`, `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`, `FRONTEND_ORIGIN`, `FRONTEND_CALLBACK_URL`, tài khoản seed `DEFAULT_ADMIN_*`/`DEFAULT_STAFF_*`/`DEFAULT_BUYER_PASSWORD`, `STRIPE_*`, `MAIL_*`, `DEVICE_HASH_SECRET` (đủ danh sách và giải thích trong `.env.example`). Frontend: sao chép `frontend/.env.example` (`NEXT_PUBLIC_API_BASE_URL`).
2. Dựng hạ tầng:
   ```bash
   docker compose up -d
   ```
3. Database cho từng service (`users_service`, `products_service`, `orders_service`, `payments_service`, `notifications_service`) được tạo tự động bởi `docker/postgres/init/01-create-databases.sql` khi volume Postgres **còn trống**. Volume cũ (đã tạo trước khi có script) thì chạy tay — script idempotent, DB đã có sẽ bỏ qua:
   ```bash
   docker exec -i postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres' < docker/postgres/init/01-create-databases.sql
   ```
4. Chạy service (mỗi module có `mvnw`), ví dụ:
   ```bash
   cd backend && mvn -pl services/user-service -am spring-boot:run
   ```
5. Frontend:
   ```bash
   cd frontend && npm ci && npm run dev
   ```

Mọi request từ trình duyệt đi qua Gateway `http://localhost:8999/api/...`.

### Thanh toán Stripe (test mode)

1. Đặt `STRIPE_SECRET_KEY` (dạng `sk_test_...`) trong môi trường của payment-service. Chỉ dùng khóa test.
2. Chuyển tiếp webhook về máy bằng Stripe CLI, lấy signing secret (`whsec_...`) nó in ra làm `STRIPE_WEBHOOK_SECRET` rồi khởi động payment-service:
   ```bash
   stripe listen --events checkout.session.completed,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,checkout.session.expired,refund.updated,refund.failed --forward-to localhost:8999/api/payments/webhooks/stripe
   ```
   Stripe CLI bản mới bắt buộc `--events`; danh sách trên là các loại payment-service xử lý.
3. Chạy đủ api-gateway, user, product, order, payment, Kafka. Giữ vé → Confirm cart → **Pay** → trả bằng thẻ test `4242 4242 4242 4242` (ngày hết hạn tương lai, CVC bất kỳ).

Thiếu khóa thì payment-service vẫn khởi động nhưng tạo phiên thanh toán trả `5101`/`4013` và mọi webhook bị từ chối (`5201`).

### Email (notification-service)

Đặt `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` (SMTP thật hoặc mail catcher bất kỳ). Thiếu `MAIL_HOST` thì service vẫn chạy, thông báo trong ứng dụng vẫn có, nhưng **email OTP đăng ký/quên mật khẩu không được gửi** (log `EMAIL_SKIPPED`, không in mã). Trình duyệt soát vé (`/check-in`) quét bằng camera trên Chrome/Edge/Android; trình duyệt khác nhập mã.

## Tài liệu

| File | Nội dung |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Kiến trúc, service, luồng dữ liệu, chống bán trùng |
| [DECISIONS.md](DECISIONS.md) | Các quyết định kỹ thuật quan trọng và lý do |
| [PROGRESS.md](PROGRESS.md) | Tiến độ theo phase, những gì đã có trong code |
| [TASKS.md](TASKS.md) | Backlog việc cần làm |
| `docs/00_roadmap_phases_v1.md` | Roadmap P0–P9, yêu cầu chức năng |
| `docs/auth/`, `docs/product/`, `docs/order/`, `docs/payment/`, `docs/notification/`, `docs/bot-detection/` | Design, API design, UI plan theo phase (P6 có thêm báo cáo model) |
| `docs/SESSION_HANDOFF.md` | Bàn giao ngữ cảnh giữa các phiên làm việc với AI |
| `CLAUDE.md`, `AGENTS.md`, `frontend/AGENTS.md` | Quy ước cho AI agent và convention code |

## Giới hạn đã biết

- Dữ liệu ML là tự tạo (k6 mô phỏng bot + thao tác thật), không phải dữ liệu production.
- Stripe chỉ ở chế độ test, tiền tệ USD.
- Integration test cần Postgres/Redis đang chạy (compose) và DB test: `docker exec -i postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres' < docker/postgres/test/create-test-databases.sql`, rồi `cd backend && mvn verify`.
- Thanh toán mới kiểm chứng bằng integration test với Stripe giả lập; chưa hỗ trợ đơn 0 đồng; buyer chưa chọn hoàn từng vé (chỉ tự động bỏ vé đã check-in).
- Bot-detection chỉ được đo trên traffic k6 tự tạo; device ID dễ bị xóa/làm giả.
- Chưa có rate limiting, chưa deploy.
