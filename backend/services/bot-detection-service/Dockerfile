FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app/ ./app/
# Model huấn luyện offline (training/train.py); thiếu thì service chạy chế độ UNSCORED (D42)
COPY model/ ./model/
EXPOSE 9005
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9005"]
