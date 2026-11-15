# Vantix — Phase 6: Bot Detection API Design

> **Trạng thái:** Hợp đồng triển khai 15/11/2026.
> **Liên quan:** P6_bot_detection_design.md, ../order/P3_order_api_design.md, ../notification/P5_notification_api_design.md.
> **Path:** Gateway expose `/api/orders/...`, `/api/bot-detection/...`; service Java có context path `/api`.

## 1. Mã lỗi mới

| Code | Error | HTTP | Trường hợp |
|---|---|---:|---|
| 4019 | BOT_REVIEW_REQUIRED | 403 | Tài khoản hoặc thiết bị có flag ACTIVE: OR01, OR04 và OR06 khi tăng số lượng |
| 4020 | BOT_FLAG_NOT_FOUND | 404 | OR15 với id không tồn tại |
| 7001 | BOT_ADMIN_REQUIRED | 403 | API quản trị của bot-detection gọi bởi người không phải ADMIN |
| 7002 | BOT_MODEL_UNAVAILABLE | 503 | `POST /score` khi chưa có model |
| 7003 | BOT_INVALID_FEATURES | 400 | Thiếu/sai feature khi gọi `POST /score` |

## 2. Header thiết bị

- Frontend gửi `X-Device-Id: <uuid>` trên mọi request tới API (tạo một lần bằng `crypto.randomUUID()`, lưu `localStorage` key `vantix.deviceId`).
- order-service chấp nhận đúng định dạng UUID; sai/thiếu → coi như không có thiết bị (không lỗi).
- `deviceHash = hex(HMAC-SHA256(DEVICE_HASH_SECRET, lowercase(uuid)))` (64 ký tự). `DEVICE_HASH_SECRET` trống → order-service sinh secret ngẫu nhiên mỗi lần khởi động và log WARN (hash không ổn định qua restart; chỉ chấp nhận ở dev).
- OR07 (availability, công khai) có header hợp lệ → ghi lần xem đầu: Redis `order:device-view:{deviceHash}:{eventId}` = epoch ms, `SET NX EX 7200`.

## 3. Order API

### Thay đổi OR01 / OR04 / OR06
Trước khi gọi Product: buyer hoặc thiết bị có flag ACTIVE → `4019`. OR06 chỉ kiểm tra khi tăng số lượng (giảm vẫn được để buyer tự nhả vé). Hold đã có không bị đụng tới.

### OR14 — Danh sách flag (ADMIN)
`GET /api/orders/bot-flags?status=ACTIVE&page=0&size=20` — `status` `ACTIVE` (đang chặn) | `WATCH` (HIGH đơn lẻ không kèm luật, chưa chặn — D46) | `CLEARED` | bỏ trống (tất cả); mới nhất (`last_flagged_at`) trước; `size` 1–100.

~~~json
{ "code": 200, "result": { "content": [ {
  "id": "uuid", "buyerId": "uuid", "deviceHash": "3f9a…(64)", "orderId": "uuid", "eventId": "uuid",
  "score": 0.7123, "modelVersion": "iforest-2026-11-15", "features": { "buyer_adds_1m": 9, "…": 0 },
  "status": "ACTIVE", "hitCount": 3, "firstFlaggedAt": "…", "lastFlaggedAt": "…",
  "clearedAt": null, "clearedBy": null } ], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 } }
~~~

### OR15 — Gỡ flag (ADMIN)
`POST /api/orders/bot-flags/{flagId}/clear` — không body. `WATCH` hoặc `ACTIVE` → `CLEARED` (`clearedAt`, `clearedBy`; UI gọi là "Dismiss" với WATCH); đã CLEARED → trả lại như cũ (idempotent). Lỗi `4020`.

## 4. Kafka

| Topic | Producer → Consumer | Payload (key = orderId) |
|---|---|---|
| `cart.item_added` (P3, **bổ sung field**) | order → bot-detection | `orderId`, `buyerId`, `eventId`, `itemCount`, `quantity`, `occurredAt`, **`deviceHash`** (null được), **`eventViewedAt`** (null được) |
| `bot_score.evaluated` (mới) | bot-detection → order | `evaluationId`, `orderId`, `buyerId`, `eventId`, `deviceHash`, `anomalyScore` (null khi UNSCORED), `riskLevel` (`LOW`\|`MEDIUM`\|`HIGH`\|`UNSCORED`), `modelVersion`, `ruleMatched` (D46, luật tay đúng), `features` (map tên → số), `occurredAt`, `evaluatedAt` |

`cart.item_added` từ nay đi qua cột `topic` + `payload` của `order_event_outbox` (như event P4/P5); bản ghi cũ dạng cột riêng vẫn được gửi như trước. Consumer group bot-detection: `bot-detection-service`. Order dùng group sẵn có `order-service`.

## 5. API nội bộ user-service

`GET /api/internal/users/{userId}/account` — `@PreAuthorize("hasRole('INTERNAL')")`, không route qua Gateway.
~~~json
{ "code": 200, "result": { "userId": "uuid", "createdAt": "2026-01-02T03:04:05Z" } }
~~~
User không có/đã xóa mềm → `2601`. Không trả email/tên (feature không cần PII).

## 6. bot-detection API (`/api/bot-detection`, qua Gateway)

| # | Method | Path | Quyền | Kết quả |
|---|---|---|---|---|
| BD01 | GET | `/health` | công khai | `{status, modelLoaded, modelVersion, consumerRunning}` |
| BD02 | GET | `/model` | ADMIN | phiên bản, danh sách feature, ngưỡng `medium`/`high`, chỉ số đánh giá lúc huấn luyện |
| BD03 | POST | `/score` | ADMIN | body `{features: {...}}` → `{anomalyScore, riskLevel, modelVersion}`; dùng để thử/giải thích, không ghi gì |

Quyền theo Trust-The-Gateway (D03): service đọc `X-User-Role` do Gateway inject; thiếu/khác `ADMIN` → `7001`. Response theo `ApiResponse` (`{code, message?, result}`) như service Java.

## 7. Cấu hình

| Service | Thuộc tính / biến | Mặc định |
|---|---|---|
| order | `app.order.device-hash-secret` / `DEVICE_HASH_SECRET` | trống → secret ngẫu nhiên mỗi lần chạy (WARN) |
| order | `app.order.device-view-ttl` | `PT2H` |
| bot-detection | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| bot-detection | `REDIS_URL` | `redis://localhost:6379/2` |
| bot-detection | `USER_SERVICE_URL` | `http://localhost:9004/api` |
| bot-detection | `BOT_MODEL_DIR` | `model/` trong service |
| bot-detection | `BOT_CONSUMER_ENABLED` | `true` |
