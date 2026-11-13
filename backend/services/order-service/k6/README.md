# k6 — tranh chấp ghế / GA (P3)

Đo bằng số liệu lớp chống bán trùng (D08) qua Gateway thật. Trạng thái: **script sẵn sàng, chưa có kết quả** — máy dev hiện chưa cài `k6` và quy ước môi trường không cho pull image `grafana/k6` (TASKS.md, P3-B).

Bằng chứng đúng đắn hiện có (không thay được số liệu tải): `OrderConcurrencyIntegrationTest` — 20 buyer tranh 1 ghế → 1 hold, 30×1 GA trên 10 vé → 10, expire song song nhả đúng một lần (D23).

## Chuẩn bị

1. Chạy hạ tầng (`docker compose up -d`), gateway, user, product, order (README gốc).
2. ADMIN tạo + publish một event có seat map, một hạng SEATED và một hạng GA (frontend admin hoặc API PR03–PR12). Ghi lại `EVENT_ID`, `TICKET_TYPE_ID` (SEATED hoặc GA), `SEAT_ID`.
3. Tài khoản BUYER: mỗi VU một buyer (mỗi buyer tối đa một CART/event). Seed có sẵn `buyer0..4@gmail.com` (mật khẩu `DEFAULT_BUYER_PASSWORD`) → tối đa 5 VU; muốn nhiều hơn thì đăng ký thêm buyer.
4. Ghi tài khoản vào `k6/.env.local` (đã gitignore, **không commit**), ví dụ:
   ```bash
   BUYER_ACCOUNTS_JSON='[{"email":"buyer0@gmail.com","password":"..."},{"email":"buyer1@gmail.com","password":"..."}]'
   ```

## Chạy

```bash
set -a; . ./.env.local; set +a
k6 run -e MODE=seat -e EVENT_ID=... -e TICKET_TYPE_ID=... -e SEAT_ID=... \
  --summary-export results/seat-$(date +%Y%m%d-%H%M).json seat-contention.js
```

```bash
# GA: GA_AVAILABLE = availableQuantity của hạng GA ngay trước khi chạy
k6 run -e MODE=ga -e EVENT_ID=... -e TICKET_TYPE_ID=... -e GA_AVAILABLE=3 \
  --summary-export results/ga-$(date +%Y%m%d-%H%M).json seat-contention.js
```

Threshold tự fail nếu số request giữ được khác kỳ vọng (seat: đúng 1; GA: `min(VU, GA_AVAILABLE)`), phần còn lại phải là 409.

Sau khi chạy, kiểm bất biến trên DB (chỉ đọc, mọi mục "phải 0 dòng"):

```bash
docker exec -i postgres sh -c 'psql -U "$POSTGRES_USER" -d orders_service -v event_id=<EVENT_ID>' < invariants.sql
```

Đo expiry lag: để các hold hết hạn (10 phút), gọi lại availability hoặc chờ job 1 giây, rồi chạy lại `invariants.sql` (mục 5: p50/p95/max giây từ deadline tới lúc hold thành `EXPIRED`).

## Báo cáo kết quả (`results/<ngày>-<mode>.md`)

Kèm file `--summary-export`. Bắt buộc ghi: commit SHA (`git rev-parse HEAD`), số VU, thời gian chạy, request rate, p50/p90/p95/p99 `http_req_duration`, error rate (ngoài 409 kỳ vọng), `hold_success`/`hold_conflict`, kết quả 5 mục `invariants.sql`, expiry lag, cấu hình máy/Docker (CPU, RAM, phiên bản Postgres/Redis/Kafka).

## Giới hạn

- Kịch bản `per-vu-iterations` 1 lần/VU: đo tính đúng dưới tranh chấp, chưa phải load test thông lượng.
- Số VU bị chặn bởi số tài khoản BUYER thật (đăng nhập qua Gateway).
- Mọi thao tác ghi trên cùng event được tuần tự hóa bằng advisory lock (D08/D23): đúng nhưng giới hạn thông lượng mỗi event — đây là điều k6 cần đo.
