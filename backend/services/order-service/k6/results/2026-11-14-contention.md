# k6 — tranh chấp ghế và GA (14/11/2026)

Chạy theo `k6/README.md`. File summary: `20261114-081847-seat.json`, `20261114-081847-ga.json`.

## Môi trường

| Mục | Giá trị |
|---|---|
| Commit backend | `f3720bc` (không đổi tới `develop` @ `64274ad`); script k6 có thêm metric `hold_duration` trong PR kèm báo cáo này |
| Máy | Laptop AMD Ryzen 5 5500U (6 nhân/12 luồng), RAM 16 GB (lúc chạy chỉ còn ~2 GB commit trống), Windows 11, Docker Desktop |
| Hạ tầng | Container compose: PostgreSQL 14.25, Redis 8.10.3, Kafka `apache/kafka:4.3.1` |
| Service | Gateway → user, product, order (jar, JDK 25). order-service chạy JVM tiết kiệm bộ nhớ: `-Xmx320m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC` (bản thường bị crash vì hết native memory); các service khác `-Xmx384m` |
| Dữ liệu | Stack E2E riêng: DB `*_e2e`, Redis DB 14; 200 buyer `k6-buyer000..199@example.com` tạo trực tiếp trong DB test; event mới: 10 ghế SEATED + 30 vé GA |
| k6 | v2.4.0, `per-vu-iterations`, 1 lần/VU, mọi VU bắn cùng lúc sau setup (đăng nhập tuần tự) |

## Kết quả

| Kịch bản | VU | Kỳ vọng | Thành công | 409 conflict | Lỗi khác | Request giữ vé/s* | `hold_duration` p50 / p90 / p95 / p99 / max |
|---|---|---|---|---|---|---|---|
| Ghế: 100 buyer cùng một ghế | 100 | 1 | **1** | **99** | 0 (0%) | ~22 | 3.11s / 4.02s / 4.25s / 4.57s / 4.61s |
| GA: 200 buyer, kho 30 vé, mỗi người 1 vé | 200 | 30 | **30** | **170** | 0 (0%) | ~26 | 5.88s / 7.22s / 7.47s / 7.67s / 7.81s |

\* Tất cả request giữ vé bắn đồng thời; tốc độ xử lý ≈ VU / max(`hold_duration`). `http_reqs/s` trong summary gộp cả bước đăng nhập ở setup nên không dùng.

Toàn bộ threshold đạt (`hold_success`, `hold_conflict`, `checks rate==1`).

## Bất biến (`invariants.sql`, ngay sau khi chạy)

| # | Bất biến | Kết quả |
|---|---|---|
| 1 | Mỗi ghế ≤ 1 hold hiệu lực | ✅ 0 vi phạm |
| 2 | GA: held + sold ≤ total, `held_quantity` = tổng hold hiệu lực | ✅ 0 vi phạm (held 30/30, 31 Order: 1 ghế + 30 GA) |
| 3 | Không có hold hiệu lực thuộc Order đã kết thúc | ✅ 0 vi phạm |
| 4 | Mỗi buyer ≤ 1 CART/event | ✅ 0 vi phạm |
| 5 | Expiry lag (deadline → hold `EXPIRED`), đo sau khi 31 hold hết hạn | ✅ p50 0.82s, p95 1.25s, max 1.32s (job `expireDueOrders` chạy mỗi 1s); sau đó `held_quantity` GA về 0, bất biến 1–4 vẫn 0 vi phạm |

## Nhận xét

1. **Tính đúng đắn đạt tuyệt đối** dưới tranh chấp: không bán trùng ghế, GA không vượt kho, không có lỗi 5xx.
2. **Độ trễ cao vì mọi request ghi trên cùng một event bị tuần tự hóa** bằng `pg_advisory_xact_lock(event)` (D08/D23): 100 request ghế xử lý xong trong ~4.6s ≈ 45 ms/transaction nối đuôi nhau; GA 200 request trong ~7.8s ≈ 39 ms/transaction — request cuối hàng phải chờ gần hết thời gian đó. Mỗi request còn gọi product-service 2 lần (event + seat map) trước khi vào khóa, cộng máy dev thiếu RAM và JVM order-service chạy JIT cấp 1.
3. **Request thua vẫn phải xếp hàng chờ khóa event** trước khi bị từ chối: lớp Redis `SET NX` cho ghế nằm *bên trong* transaction đã khóa event, nên không cắt sớm được request tranh ghế đã bị giữ.
4. Gợi ý (chưa làm, cần quyết định vì đụng thiết kế chống bán trùng):
   - Kiểm tra/giữ key Redis của ghế **trước** khi lấy khóa event — fail nhanh cho phần lớn request thua (vẫn giữ unique index DB làm lớp cuối).
   - Với GA, `tryHold` đã là update nguyên tử có điều kiện; cân nhắc bỏ khóa event cho đường chỉ có GA.
   - Cache catalog Product ngắn hạn (vài giây) cho đường giữ vé.
   - Đo lại trên máy đủ RAM / JVM cấu hình thường trước khi kết luận về thông lượng tuyệt đối.
