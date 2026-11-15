# Vantix — Phase 6: Báo cáo bot-detection

> **Ngày:** 15/11/2026 · model `iforest-20261114` · chi tiết và biểu đồ: `backend/services/bot-detection-service/training/notebooks/P6_bot_detection_report.ipynb`, số liệu thô: `training/reports/metrics.json`.
> **Đọc trước:** mọi dữ liệu là **tự tạo** bằng k6. Kết quả chỉ cho thấy model tách được các kiểu hành vi mà chính kịch bản giả định — **không** phải bằng chứng hiệu quả trên traffic thật.

## 1. Cách làm

- Stack thật trên DB `*_e2e`: Gateway → order-service (device hash, lượt xem) → outbox → Kafka → bot-detection.
- k6 (`datagen/traffic.js`), mỗi vòng 256 tài khoản:
  - 200 **người dùng**: xem sự kiện, suy nghĩ 5–90 giây (lệch phải), mua 1–4 vé. Một phần thêm vé lần hai hoặc mua sự kiện khác. 12% tài khoản mới (< 24 giờ). Thiết bị gia đình dùng chung.
  - 18 **botfast**: dồn dập, không xem trang, hủy giỏ rồi giữ lại. Nửa số bot không gửi thiết bị, nửa đổi thiết bị mỗi request.
  - 14 **botviewer**: gọi availability rồi thêm ngay trong 0,3–1,5 giây, gom 3–4 sự kiện với số lượng lớn.
  - 24 **botfarm**: 24 tài khoản trên 3 thiết bị, mỗi tài khoản thêm giỏ ít giống người dùng. 30% bot dùng tài khoản cũ.
- Vòng **A** (770 lần thêm giỏ) dùng cho train/validation, chia theo tài khoản 75/25. Vòng **B** (768 lần, tài khoản và lần chạy khác) là tập test.
- Isolation Forest: 300 cây, `contamination="auto"`, 12 feature (xem design mục 4), `log1p` cho đại lượng đếm và thời gian.
- Ngưỡng HIGH là phân vị 99% điểm của người dùng trên validation; MEDIUM là phân vị 95%.

## 2. Kết quả trên vòng B (test)

| | Precision | Recall | F1 | HIGH ở người dùng (lần thêm giỏ) | Người dùng bị chặn (tài khoản) |
|---|---:|---:|---:|---:|---:|
| **Isolation Forest** (train lấy mẫu 10% bot) | 0.985 | 0.933 | 0.958 | 2.5% | 7 / 200 (3.5%) |
| Isolation Forest học trên tỉ lệ tự nhiên (65% bot) | 0.878 | 0.132 | 0.230 | 3.3% | — |
| Luật tay (`buyer_adds_1m ≥ 4` hoặc ≥ 4 tài khoản/thiết bị) | 1.000 | 0.782 | 0.878 | 0% | 0 / 200 |

ROC-AUC 0.988, average precision 0.994.

| Loại | Tỉ lệ lần thêm giỏ bị HIGH | Tài khoản bị chặn | Số lần thêm lọt trước khi chặn (trung vị) |
|---|---:|---:|---:|
| botfast | 98.3% | 18/18 | 0 |
| botviewer | 81.3% | 14/14 | 1 |
| botfarm | 72.7% | 17/24 | 0 |
| human | 2.5% | 7/200 | — |

## 3. Đọc kết quả một cách trung thực

1. **Bot học trên tỉ lệ tự nhiên thì model gần như vô dụng** (recall 13%). Trong kịch bản, bot sinh 65% lần thêm giỏ; Isolation Forest coi cụm `botfast` dày đặc là "bình thường". Model dùng được chỉ vì tập train được lấy mẫu (dùng nhãn) để bot còn 10%. Production không có nhãn nên phải có cách khác để giữ tập train "sạch", ví dụ huấn luyện ngoài giờ mở bán hoặc lọc trước bằng luật tay.
2. **Luật tay đơn giản cạnh tranh được.** Nó không chặn nhầm người dùng nào nhưng bỏ sót 22% lần thêm giỏ của bot. Isolation Forest bắt được nhiều hơn (`botviewer`, `botfarm`) nhưng chặn nhầm 7 người dùng. Hai cách nên được cân nhắc cùng nhau thay vì coi model là thay thế.
3. **Ngưỡng 1% không giữ được trên test** (thực tế 2.5% lần thêm giỏ của người dùng bị HIGH). Validation chỉ có khoảng 60 lần thêm giỏ của người dùng nên phân vị 99% rất nhiễu. Tính theo tài khoản thì 3.5% người dùng bị chặn: chấp nhận được với cơ chế review của ADMIN, nhưng không nhỏ.
4. **`botfarm` khó nhất.** Mỗi tài khoản cư xử gần như người dùng; tín hiệu chủ yếu là nhiều tài khoản trên một thiết bị và tài khoản mới. Bot chỉ cần đổi device ID là mất tín hiệu này (D37).
5. Kịch bản bot và người dùng do cùng một người viết. Phân bố thời gian suy nghĩ là giả định, không đo từ người thật; quy mô nhỏ (một máy dev, khoảng 1.500 lần thêm giỏ).

## 4. Kiểm tra live (vòng C, model đã nạp vào service)

Stack e2e chạy lại với model đã nạp (`/health`: `modelLoaded=true`). k6 vòng C dùng cùng kịch bản, tài khoản mới, thời gian thật. Toàn bộ đường đi chạy thật: Order → Kafka → bot-detection (Redis, user-service) → Kafka → `bot_flags` → 4019.

| | Kết quả |
|---|---|
| Lần thêm giỏ thành công / bị chặn 4019 / lỗi khác | 348 / 133 / 1 |
| botfast bị flag | 18/18 |
| botviewer bị flag | 14/14 |
| botfarm bị flag | **4/24** |
| Người dùng bị flag (chặn nhầm) | **16/200 (8%)** |

**Live tệ hơn offline** ở hai điểm: chặn nhầm người dùng 8% (offline 3.5%), và botfarm chỉ bị bắt 4/24 (offline 17/24). Nguyên nhân chưa xác minh. Giả thuyết, chưa kiểm chứng:
- Khi chặn thật, bot bị chặn sớm nên các lần thêm giỏ sau không xảy ra, làm phân bố feature khác dữ liệu offline (vốn không bị chặn).
- Redis của bot-detection giữ cửa sổ từ các vòng trước.
- Ngưỡng chọn trên validation nhỏ.

Với tỉ lệ chặn nhầm này, **chưa nên bật chặn tự động ngoài môi trường demo**. Xem mục 5.

## 5. Việc nên làm tiếp

- Điều tra chênh lệch live/offline: xuất `bot_score.evaluated` của vòng C, so phân bố feature với vòng B.

- Thu traffic người dùng thật (thao tác tay) thay cho kịch bản; đo lại tỉ lệ chặn nhầm.
- Kết hợp luật tay và model; cân nhắc chỉ chặn khi HIGH lặp lại, hoặc MEDIUM kèm luật tay, để giảm chặn nhầm.
- Thông báo cho ADMIN khi có flag mới (Notification, TASKS P5).
- Theo dõi drift: tỉ lệ HIGH theo giờ và theo sự kiện (P9 observability).
