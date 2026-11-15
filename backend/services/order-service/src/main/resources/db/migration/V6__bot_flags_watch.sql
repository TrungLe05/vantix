-- D46: HIGH đơn lẻ không kèm luật tay chỉ theo dõi (WATCH, không chặn); HIGH kèm luật hoặc HIGH lần hai → ACTIVE.
-- Mỗi buyer tối đa một dòng đang mở (WATCH hoặc ACTIVE) để upsert gộp được.
ALTER TABLE bot_flags DROP CONSTRAINT bot_flags_status_check;
ALTER TABLE bot_flags ADD CONSTRAINT bot_flags_status_check CHECK (status IN ('WATCH', 'ACTIVE', 'CLEARED'));

ALTER TABLE bot_flags DROP CONSTRAINT ck_bot_flags_cleared;
ALTER TABLE bot_flags ADD CONSTRAINT ck_bot_flags_cleared CHECK (
    (status IN ('WATCH', 'ACTIVE') AND cleared_at IS NULL AND cleared_by IS NULL)
    OR (status = 'CLEARED' AND cleared_at IS NOT NULL AND cleared_by IS NOT NULL)
);

DROP INDEX uq_bot_flags_active_buyer;
CREATE UNIQUE INDEX uq_bot_flags_open_buyer ON bot_flags (buyer_id) WHERE status IN ('WATCH', 'ACTIVE');
