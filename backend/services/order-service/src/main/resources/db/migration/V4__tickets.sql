-- P5: vé do Order phát hành sau khi thanh toán thành công (Order sở hữu vé và check-in).
-- Mỗi dòng là một vé vào cổng: ghế SEATED = 1 vé, GA số lượng N = N vé.

CREATE TABLE tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL REFERENCES order_items (id) ON DELETE CASCADE,
    event_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    ticket_type_id UUID NOT NULL,
    seat_id UUID,
    ticket_name_snapshot VARCHAR(200) NOT NULL,
    seat_label_snapshot VARCHAR(120),
    -- Nội dung QR: chuỗi ngẫu nhiên 192 bit, không đoán được, không suy ra từ id
    code VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('VALID', 'CHECKED_IN', 'VOID')),
    checked_in_at TIMESTAMPTZ,
    -- userId của ADMIN/STAFF đã quét (trả lại khi quét trùng)
    checked_in_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tickets_code UNIQUE (code),
    -- Đã check-in thì phải biết lúc nào và ai quét; VOID giữ nguyên dấu vết check-in nếu có
    CONSTRAINT ck_tickets_checked_in CHECK (
        status <> 'CHECKED_IN' OR (checked_in_at IS NOT NULL AND checked_in_by IS NOT NULL)
    )
);

CREATE INDEX idx_tickets_order_id ON tickets (order_id);
