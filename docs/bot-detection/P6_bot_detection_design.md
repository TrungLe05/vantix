# Vantix — Phase 6: Bot Detection Design

> **Trạng thái:** Thiết kế chốt và triển khai 15/11/2026. Lựa chọn của chủ repo (15/11/2026): device ID ngẫu nhiên không dùng thư viện fingerprint, chặn + trang review cho ADMIN, báo cáo bằng Jupyter notebook + script.
> **Liên quan:** ../00_roadmap_phases_v1.md (P6), ../order/P3_order_design.md, P6_bot_detection_api_design.md, P6_bot_detection_ui_plan.md, P6_bot_detection_report.md, `DECISIONS.md` D37–D42.

## 1. Mục tiêu và nguyên tắc

- Isolation Forest (không giám sát) chấm điểm **mỗi lần thêm vé vào giỏ**; dữ liệu huấn luyện **tự tạo** bằng k6 (bot + người dùng mô phỏng) vì không có dataset công khai phù hợp (roadmap).
- Order → bot-detection **bất đồng bộ** qua Kafka: thêm giỏ không chậm đi, bot-detection sập thì bán vé vẫn chạy (fail open — khác Product fail closed vì đây là lớp phòng vệ bổ sung, không phải điều kiện đúng đắn).
- Rủi ro cao → **chặn các lần thêm giỏ tiếp theo** của tài khoản đó và thiết bị đó + đánh dấu để ADMIN review. **Không hủy hold hiện tại** (model có thể sai).
- Dữ liệu cá nhân (liên kết P8): feature không chứa tên/email/IP; thiết bị chỉ đi dưới dạng **HMAC** (`deviceHash`), device ID thô không rời order-service.

## 2. Quyết định

| # | Quyết định | Lý do ngắn |
|---|---|---|
| D37 | Device ID = UUID ngẫu nhiên do frontend tạo một lần (localStorage), gửi header `X-Device-Id`; order-service chỉ lưu/phát `HMAC-SHA256(DEVICE_HASH_SECRET, id)` | Không thêm dependency, không thu thập đặc điểm trình duyệt; đánh đổi: bot xóa storage là có ID mới (đặc trưng "nhiều tài khoản/thiết bị" chỉ bắt được bot lười) |
| D38 | `cart.item_added` thêm `deviceHash`, `eventViewedAt` (bổ sung field, không đổi field cũ); "đã xem sự kiện" ghi khi thiết bị gọi availability (OR07) | Order là nơi duy nhất thấy cả hai tín hiệu; availability là API mà trang chi tiết/sơ đồ ghế đều gọi |
| D39 | bot-detection giữ cửa sổ trượt trong Redis (sorted set theo thời điểm của event), tính feature theo `occurredAt` của bản tin; tuổi tài khoản lấy từ user-service `/internal/users/{id}/account` (role INTERNAL), cache 24 giờ | Cùng một code tính feature online và khi tái hiện dữ liệu offline; Kafka phát lại không đếm trùng (member = khóa của bản tin) |
| D40 | Mô hình huấn luyện offline từ log k6, artifact (`joblib` + `model_meta.json`) commit vào repo; ngưỡng HIGH chọn trên tập validation với ràng buộc tỉ lệ người thật bị gắn HIGH ≤ 1% | Model nhỏ, tái lập được; ưu tiên ít phạt oan hơn bắt được mọi bot |
| D41 | Order consume `bot_score.evaluated`; `HIGH` → `bot_flags` ACTIVE (một dòng ACTIVE/buyer). Thêm giỏ (OR01, OR04, OR06 tăng số lượng) bị chặn `4019` nếu buyer **hoặc** thiết bị có flag ACTIVE. ADMIN xem/gỡ (OR14, OR15) | Chặn theo cả hai vì bot đổi được thiết bị (D37) và dùng được nhiều tài khoản; gỡ flag giữ lịch sử |
| D42 | Thiếu model (chưa huấn luyện) → bot-detection vẫn tính feature và phát `riskLevel = UNSCORED` | Dùng chính pipeline online để thu dữ liệu huấn luyện; Order bỏ qua mọi mức khác `HIGH` |

D19 (OTP xác thực thiết bị) **vẫn mở**: P6 không cần "thiết bị tin cậy"; gỡ chặn do ADMIN làm tay.

## 3. Luồng

~~~mermaid
sequenceDiagram
    participant F as Frontend (X-Device-Id)
    participant O as order-service
    participant R as Redis (order)
    participant K as Kafka
    participant B as bot-detection
    participant U as user-service
    F->>O: GET /orders/events/{id}/availability
    O->>R: SET device-view:{hash}:{eventId} = now NX EX 2h
    F->>O: POST /orders (thêm giỏ)
    O->>O: flag ACTIVE theo buyer/thiết bị? → 4019
    O->>O: hold + outbox cart.item_added {deviceHash, eventViewedAt}
    O->>K: cart.item_added (key = orderId)
    K->>B: consume
    B->>U: GET /internal/users/{id}/account (cache 24h)
    B->>B: cập nhật cửa sổ Redis, tính feature, Isolation Forest
    B->>K: bot_score.evaluated {anomalyScore, riskLevel, features}
    K->>O: consume: HIGH → bot_flags ACTIVE
    F->>O: POST /orders lần sau → 4019 (hold cũ giữ nguyên)
~~~

## 4. Feature (tính tại thời điểm `occurredAt` của lần thêm giỏ)

| Feature | Ý nghĩa | Ghi chú |
|---|---|---|
| `buyer_adds_1m`, `buyer_adds_10m` | Số lần thêm giỏ của tài khoản trong 1 / 10 phút (gồm lần này) | tốc độ |
| `device_adds_1m` | Số lần thêm giỏ từ thiết bị trong 1 phút | 0 khi không có thiết bị |
| `secs_since_prev_add` | Giây kể từ lần thêm giỏ trước của tài khoản | trần 3600; lần đầu = 3600 |
| `device_accounts_24h` | Số tài khoản khác nhau trên thiết bị trong 24 giờ | 1 khi không có thiết bị |
| `account_age_hours` | Tuổi tài khoản | trần 8760; dùng `log1p` khi huấn luyện |
| `viewed_event` | Thiết bị đã gọi availability của event trước khi thêm | 0/1 |
| `secs_since_view` | Giây từ lần xem đầu tới lúc thêm | trần 1800; 0 khi chưa xem |
| `quantity`, `item_count` | Số vé / số dòng của lần thêm | |
| `buyer_events_1h` | Số event khác nhau tài khoản thêm giỏ trong 1 giờ | gom vé nhiều sự kiện |
| `has_device` | Request có `X-Device-Id` hợp lệ | client tự viết thường không gửi |

## 5. Mô hình dữ liệu (`orders_service` — `V5__bot_flags.sql`)

~~~mermaid
erDiagram
    BOT_FLAGS {
        uuid id PK
        uuid buyer_id "UNIQUE khi status = ACTIVE"
        string device_hash "HMAC, có thể null"
        uuid order_id "lần thêm giỏ bị gắn cờ gần nhất"
        uuid event_id
        numeric score "điểm cao nhất"
        string model_version
        jsonb features "feature của lần cao nhất"
        string status "ACTIVE | CLEARED"
        int hit_count
        timestamp first_flagged_at
        timestamp last_flagged_at
        timestamp cleared_at
        uuid cleared_by "ADMIN"
    }
~~~

Bản tin `HIGH` lặp lại cho buyer đang ACTIVE → cộng `hit_count`, giữ điểm cao nhất. Bản tin có `evaluatedAt` cũ hơn lần ADMIN gỡ gần nhất của buyer bị bỏ qua (Kafka phát lại không gắn cờ lại).

## 6. Sinh dữ liệu, huấn luyện, đánh giá

- k6 (`bot-detection-service/k6/traffic.js`) chạy qua Gateway thật trên stack `*_e2e`: kịch bản **người dùng** (xem sự kiện, suy nghĩ 5–90 giây, mua 1–4 vé, mỗi người một thiết bị) và ba kiểu **bot** (dồn dập không xem trang; xem trang nhưng không chờ; xoay thiết bị/nhiều tài khoản trên ít thiết bị).
- Tài khoản seed trực tiếp ở `users_service_e2e` với `created_at` khác nhau; nhãn = tiền tố email (`dg-human-*`, `dg-bot-*`) — chỉ script đánh giá biết nhãn, model không thấy.
- Bản tin `cart.item_added` được ghi lại (JSONL, không PII) rồi **tái hiện offline bằng cùng code feature** → dataset CSV. Dataset chia train/validation/test **theo tài khoản** (một tài khoản chỉ nằm ở một tập). Model học trên tập train **không có nhãn** (trộn bot và người); nhãn chỉ dùng để chọn ngưỡng trên validation và đo kết quả trên test.
- Báo cáo: `training/notebooks/P6_bot_detection_report.ipynb` (EDA, feature, đánh giá) + `docs/bot-detection/P6_bot_detection_report.md` (tóm tắt và giới hạn).

## 7. Giới hạn đã biết

- Dữ liệu hoàn toàn tự tạo: bot và người dùng đều do chính nhóm viết kịch bản, nên độ chính xác cao **không** chứng minh hiệu quả trên traffic thật.
- Device ID dễ làm giả/xóa; header có thể bị client giả lập bỏ qua (feature `has_device` chỉ bắt client lười).
- "Đã xem" chỉ dựa vào availability; bot gọi availability trước là vượt qua.
- Không có cơ chế tự gỡ chặn; ADMIN phải gỡ tay. Chưa thông báo cho ADMIN khi có flag mới (TASKS).
- Một instance bot-detection; cửa sổ Redis dùng chung nên chạy nhiều instance được, nhưng thứ tự bản tin chỉ đảm bảo trong một partition (key = orderId).
