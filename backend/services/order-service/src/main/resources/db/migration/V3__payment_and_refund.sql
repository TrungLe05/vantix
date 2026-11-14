-- P4: Order nhận kết quả thanh toán và sở hữu việc tính/hoàn tiền (D07, D16).

-- Thêm trạng thái hoàn tiền. Không dùng IF EXISTS: nếu tên constraint lệch thì migration phải lỗi
-- rõ ràng, thay vì âm thầm để constraint cũ chặn trạng thái mới.
ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check CHECK (status IN (
    'CART', 'PENDING_PAYMENT', 'PAID', 'TICKETS_ISSUED', 'CANCELLED', 'EXPIRED',
    'REFUND_PENDING', 'REFUNDED'
));

ALTER TABLE orders ADD COLUMN paid_at TIMESTAMPTZ;

-- Mỗi Order tối đa một lần hoàn (hoàn cả đơn). UNIQUE (order_id) là lớp chặn cuối cho yêu cầu hoàn
-- lặp: buyer bấm hai lần, consumer hủy event/payment.succeeded nhận lại bản tin.
CREATE TABLE order_refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    buyer_id UUID NOT NULL,
    reason VARCHAR(30) NOT NULL CHECK (reason IN ('BUYER_REQUEST', 'EVENT_CANCELLED', 'LATE_PAYMENT')),
    refund_percent INT NOT NULL CHECK (refund_percent IN (50, 100)),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED', 'COMPLETED', 'FAILED')),
    -- Mã lỗi ngắn do Payment trả về, không phải message tự do
    failure_reason VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_order_refunds_order_id UNIQUE (order_id)
);

-- Outbox dùng chung cho nhiều loại event: bản ghi cũ (cart.item_added) giữ nguyên các cột riêng,
-- event mới mang topic + payload JSON.
ALTER TABLE order_event_outbox
    ADD COLUMN topic VARCHAR(100) NOT NULL DEFAULT 'cart.item_added',
    ADD COLUMN payload TEXT,
    ALTER COLUMN item_count DROP NOT NULL,
    ALTER COLUMN quantity DROP NOT NULL;
