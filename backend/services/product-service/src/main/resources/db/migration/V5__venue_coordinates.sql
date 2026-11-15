-- D45: venue mới phải có tọa độ chọn từ kết quả tìm địa chỉ (OpenStreetMap), tránh địa chỉ gõ tay không có thật.
-- Venue cũ giữ NULL (không tự đoán tọa độ); chỉ API tạo mới bắt buộc.
ALTER TABLE venues
    ADD COLUMN latitude NUMERIC(9, 6),
    ADD COLUMN longitude NUMERIC(9, 6),
    ADD CONSTRAINT ck_venues_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    );
