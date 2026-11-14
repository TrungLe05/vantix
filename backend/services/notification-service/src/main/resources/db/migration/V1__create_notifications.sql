-- Thông báo trong ứng dụng (P5). Email gửi theo trạng thái email_status ngay trên dòng này
-- (giống outbox: ghi trước, scheduler gửi + retry), nên consumer Kafka không phải chờ SMTP.
-- Email chứa OTP / mật khẩu tạm (user.*) KHÔNG lưu ở đây.

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    type VARCHAR(40) NOT NULL CHECK (type IN ('TICKETS_ISSUED', 'REFUND_REQUESTED', 'REFUND_COMPLETED')),
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    -- Đường dẫn tương đối trên frontend, ví dụ /orders/{id}
    link VARCHAR(300),
    order_id UUID,
    -- Một bản tin nguồn → tối đa một thông báo: consumer nhận lặp (at-least-once) không tạo bản sao
    dedup_key VARCHAR(150) NOT NULL,
    read_at TIMESTAMPTZ,
    email_status VARCHAR(20) NOT NULL CHECK (email_status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    email_attempts INT NOT NULL DEFAULT 0,
    -- NULL = không còn lần gửi nào (đã gửi, bỏ qua hoặc hết lượt retry)
    next_email_attempt_at TIMESTAMPTZ DEFAULT now(),
    -- Chỉ lưu loại lỗi, không lưu message (có thể chứa địa chỉ email)
    email_last_error VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notifications_dedup_key UNIQUE (dedup_key)
);

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_email_due
    ON notifications (next_email_attempt_at)
    WHERE email_status IN ('PENDING', 'FAILED') AND next_email_attempt_at IS NOT NULL;
