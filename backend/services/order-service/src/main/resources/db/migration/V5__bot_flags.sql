-- P6: kết quả rủi ro cao từ bot-detection (D41). Một dòng ACTIVE mỗi buyer; gỡ chặn chuyển sang CLEARED
-- và giữ lại làm lịch sử review.
CREATE TABLE bot_flags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_id UUID NOT NULL,
    -- HMAC của device ID (D37), không bao giờ là ID thô
    device_hash VARCHAR(64),
    order_id UUID,
    event_id UUID,
    score NUMERIC(8, 4) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    -- JSON object tên feature → giá trị của lần điểm cao nhất (không có PII)
    features TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'CLEARED')),
    hit_count INT NOT NULL DEFAULT 1 CHECK (hit_count > 0),
    first_flagged_at TIMESTAMPTZ NOT NULL,
    last_flagged_at TIMESTAMPTZ NOT NULL,
    cleared_at TIMESTAMPTZ,
    cleared_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_bot_flags_cleared CHECK (
        (status = 'ACTIVE' AND cleared_at IS NULL AND cleared_by IS NULL)
        OR (status = 'CLEARED' AND cleared_at IS NOT NULL AND cleared_by IS NOT NULL)
    )
);

-- Lớp chặn cuối cho upsert đồng thời: hai bản tin HIGH cùng buyer không tạo hai dòng ACTIVE
CREATE UNIQUE INDEX uq_bot_flags_active_buyer ON bot_flags (buyer_id) WHERE status = 'ACTIVE';

-- Kiểm tra chặn theo thiết bị ở mỗi lần thêm giỏ
CREATE INDEX idx_bot_flags_active_device ON bot_flags (device_hash)
    WHERE status = 'ACTIVE' AND device_hash IS NOT NULL;

-- OR14: danh sách theo trạng thái, mới nhất trước
CREATE INDEX idx_bot_flags_status_last ON bot_flags (status, last_flagged_at DESC);

-- Bỏ qua bản tin phát lại cũ hơn lần gỡ chặn gần nhất của buyer
CREATE INDEX idx_bot_flags_buyer_cleared ON bot_flags (buyer_id, cleared_at DESC) WHERE status = 'CLEARED';
