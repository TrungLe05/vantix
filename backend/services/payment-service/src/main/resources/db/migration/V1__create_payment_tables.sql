-- Payment chỉ biết tiền (D07): tham chiếu Order bằng order_id, không lưu vé/ghế.

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Một Order chỉ có một payment: UNIQUE là lớp chặn cuối cho việc khởi tạo thanh toán lặp
    order_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED')),
    stripe_session_id VARCHAR(255),
    stripe_payment_intent_id VARCHAR(255),
    checkout_url TEXT,
    -- Deadline hold của Order; quá hạn thì Payment tự expire phiên Stripe
    expires_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_order_id UNIQUE (order_id),
    CONSTRAINT uq_payments_stripe_session_id UNIQUE (stripe_session_id)
);

CREATE INDEX idx_payments_pending_expiry ON payments (expires_at) WHERE status = 'PENDING';

CREATE TABLE refunds (
    -- id do order-service sinh (refundId trong order.refund-requested) → consumer idempotent theo khóa chính
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments (id),
    order_id UUID NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    reason VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    stripe_refund_id VARCHAR(255),
    -- Chỉ lưu mã lỗi ngắn, không lưu message tự do
    failure_reason VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refunds_stripe_refund_id UNIQUE (stripe_refund_id)
);

CREATE INDEX idx_refunds_payment_id ON refunds (payment_id);
CREATE INDEX idx_refunds_pending ON refunds (created_at) WHERE status = 'PENDING';

-- Stripe có thể gửi lại cùng một webhook: event id đã xử lý được ghi cùng transaction với thay đổi trạng thái
CREATE TABLE stripe_webhook_events (
    event_id VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transactional outbox (D11, D25): cùng cơ chế backoff/dead-letter với order_event_outbox
CREATE TABLE payment_event_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic VARCHAR(100) NOT NULL,
    message_key VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(200),
    dead_at TIMESTAMPTZ
);

CREATE INDEX idx_payment_event_outbox_pending
    ON payment_event_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL AND dead_at IS NULL;
