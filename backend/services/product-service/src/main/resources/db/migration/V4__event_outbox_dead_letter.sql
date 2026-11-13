-- Dead-letter cho outbox: sau app.outbox.max-attempts lần gửi lỗi, bản ghi bị đánh dấu dead_at
-- và scheduler không lấy nữa (xử lý tay, xem P2_product_api_design.md mục 8).
-- last_error chỉ lưu tên loại exception, không lưu message (có thể chứa dữ liệu).
ALTER TABLE event_outbox
    ADD COLUMN last_error VARCHAR(200),
    ADD COLUMN dead_at TIMESTAMPTZ;

DROP INDEX idx_event_outbox_pending;
CREATE INDEX idx_event_outbox_pending
    ON event_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL AND dead_at IS NULL;
