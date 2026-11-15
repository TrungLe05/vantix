# bot-detection-service (P6)

FastAPI + aiokafka. Consume `cart.item_added`, tính feature (cửa sổ Redis theo thời điểm bản tin), chấm điểm bằng
Isolation Forest đã huấn luyện offline, publish `bot_score.evaluated`. Thiết kế: `docs/bot-detection/`.

```
app/        config, features (Redis + bộ nhớ), model, scoring, consumer, user_client, main (API BD01–BD03)
model/      isolation_forest.joblib + model_meta.json (artifact commit vào repo, D40)
datagen/    sinh dữ liệu: run_stack.py, seed_accounts.py, setup_events.py, traffic.js (k6), export_events.py
training/   build_dataset.py, train.py, make_notebook.py, data/ (log + feature, không PII), reports/, notebooks/
tests/      pytest (test Redis tự skip nếu không có Redis; CI có service container)
```

## Chạy service

```bash
pip install -r requirements-dev.txt
```
```bash
uvicorn app.main:app --port 9005
```

Biến môi trường: `KAFKA_BOOTSTRAP_SERVERS` (mặc định `localhost:9092`), `REDIS_URL` (`redis://localhost:6379/2`),
`USER_SERVICE_URL` (`http://localhost:9004/api`), `BOT_MODEL_DIR` (`model/`), `BOT_CONSUMER_ENABLED` (`true`),
`BOT_CONSUMER_GROUP` (`bot-detection-service`). Thiếu `model/` → chế độ `UNSCORED` (D42).

## Test

```bash
python -m pytest -q
```

## Sinh dữ liệu và huấn luyện lại

Chạy trên DB `*_e2e` (không đụng dữ liệu dev), cần compose đang chạy, jar của gateway/user/product/order đã build
(`mvn -pl services/user-service,services/product-service,services/order-service,services/api-gateway -am package -DskipTests`)
và k6. Dùng `python -I -X utf8` trên Windows để in được tiếng Việt.

```bash
python -I -X utf8 datagen/run_stack.py start
```
```bash
python -I -X utf8 datagen/seed_accounts.py --round A && python -I -X utf8 datagen/setup_events.py --round A
```
```bash
cd datagen && k6 run -e ROUND=a traffic.js
```
```bash
python -I -X utf8 datagen/export_events.py --round A
```

Lặp lại cho vòng B (tập test). Sau đó:

```bash
python -I -X utf8 training/build_dataset.py A B && python -I -X utf8 training/train.py && python -I -X utf8 training/make_notebook.py
```
```bash
python -I -X utf8 datagen/run_stack.py stop
```

`datagen/.local/` (mật khẩu tài khoản seed, log, secret hash thiết bị của stack e2e) đã gitignore — không commit.
Kết quả và giới hạn: `docs/bot-detection/P6_bot_detection_report.md`.
