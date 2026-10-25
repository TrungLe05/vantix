-- Seed dữ liệu phục vụ thử nghiệm admin trong môi trường development.
-- UUID cố định để có thể dùng lại khi test API; thời gian sự kiện tính từ lúc migration chạy.
-- Có 8 DRAFT, 8 PUBLISHED và 4 CANCELLED; gồm GA, SEATED, event hỗn hợp và draft chưa đủ điều kiện publish.

INSERT INTO venues (id, name, address, city)
VALUES
    ('10000000-0000-4000-8000-000000000001', 'Nhà hát Thành phố', '7 Công trường Lam Sơn, Bến Nghé', 'TP. Hồ Chí Minh'),
    ('10000000-0000-4000-8000-000000000002', 'Nhà thi đấu Phú Thọ', '1 Lữ Gia, Phường 15', 'TP. Hồ Chí Minh'),
    ('10000000-0000-4000-8000-000000000003', 'Cung Hội nghị Quốc tế', '32 Trần Phú, Lộc Thọ', 'Nha Trang'),
    ('10000000-0000-4000-8000-000000000004', 'Công viên Yên Sở', 'Đường Pháp Vân, Hoàng Mai', 'Hà Nội'),
    ('10000000-0000-4000-8000-000000000005', 'Nhà hát Trưng Vương', '86 Hùng Vương, Hải Châu', 'Đà Nẵng')
ON CONFLICT (id) DO NOTHING;

WITH event_seed (event_no, venue_no, name, description, day_offset, duration_hours, status, sales_days_before, banner_image_url) AS (
    VALUES
        (1, 1, 'Đêm nhạc bên sông', 'Bản nháp chưa có hạng vé, dùng để kiểm tra điều kiện không thể xuất bản.', 45, 3, 'DRAFT', 30, 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a'),
        (2, 2, 'Ngày hội sách và sáng tạo', 'Sự kiện vé phổ thông với số lượng giới hạn.', 52, 7, 'DRAFT', 28, 'https://images.unsplash.com/photo-1507842217343-583bb7270b66'),
        (3, 3, 'Gala âm nhạc biển xanh', 'Bản nháp có ghế chưa được gán hạng vé để kiểm tra publish validation.', 60, 4, 'DRAFT', 35, 'https://images.unsplash.com/photo-1506157786151-b8491531f063'),
        (4, 1, 'Đêm hòa nhạc mùa thu', 'Hòa nhạc ghế ngồi với sơ đồ nhỏ, đã gán đủ ghế.', 68, 3, 'DRAFT', 32, 'https://images.unsplash.com/photo-1501612780327-45045538702b'),
        (5, 5, 'Lễ hội văn hóa miền Trung', 'Bản nháp kết hợp vé ghế ngồi và vé phổ thông.', 75, 8, 'DRAFT', 40, 'https://images.unsplash.com/photo-1459749411175-04bf5292ceea'),
        (6, 4, 'Workshop thiết kế trải nghiệm', 'Hai hạng vé phổ thông với mức giá khác nhau.', 82, 6, 'DRAFT', 35, 'https://images.unsplash.com/photo-1516321318423-f06f85e504b3'),
        (7, 2, 'Đêm kịch nói', 'Bản nháp có ghế ngồi và hạng vé đã sẵn sàng.', 90, 2, 'DRAFT', 40, 'https://images.unsplash.com/photo-1503095396549-807759245b35'),
        (8, 3, 'Chợ phiên ẩm thực', 'Sự kiện ngoài trời sử dụng vé phổ thông.', 97, 9, 'DRAFT', 45, 'https://images.unsplash.com/photo-1555939594-58d7cb561ad1'),
        (9, 1, 'Đêm nhạc acoustic phố cổ', 'Đêm nhạc acoustic với vé phổ thông.', 35, 3, 'PUBLISHED', 25, 'https://images.unsplash.com/photo-1516280440614-37939bbacd81'),
        (10, 2, 'Chung kết giải đấu e-sports', 'Sơ đồ khán đài theo khu vực, vé đã mở bán.', 42, 6, 'PUBLISHED', 30, 'https://images.unsplash.com/photo-1542751371-adc38448a05e'),
        (11, 5, 'Đêm nhạc Vantix Live', 'Sự kiện hỗn hợp gồm ghế VIP và vé đứng phổ thông.', 49, 4, 'PUBLISHED', 30, 'https://images.unsplash.com/photo-1459749411175-04bf5292ceea'),
        (12, 4, 'Hội chợ khởi nghiệp', 'Hai hạng vé phổ thông cho khách thường và khách chuyên ngành.', 56, 8, 'PUBLISHED', 35, 'https://images.unsplash.com/photo-1556761175-b413da4baf72'),
        (13, 3, 'Hòa nhạc giao hưởng mùa hè', 'Chỗ ngồi được chia theo khu A và khu B.', 63, 3, 'PUBLISHED', 40, 'https://images.unsplash.com/photo-1465847899084-d164df4dedc6'),
        (14, 1, 'Triển lãm nghệ thuật đương đại', 'Vé miễn phí số lượng giới hạn để kiểm tra giá bằng không.', 70, 10, 'PUBLISHED', 45, 'https://images.unsplash.com/photo-1531058020387-3be344556be6'),
        (15, 2, 'Đêm diễn hài độc thoại', 'Sơ đồ ghế nhỏ phù hợp kiểm tra luồng chọn ghế.', 77, 2, 'PUBLISHED', 40, 'https://images.unsplash.com/photo-1585699324551-f6c309eedeca'),
        (16, 4, 'Ngày hội chạy bộ cộng đồng', 'Vé phổ thông cho hoạt động cộng đồng ngoài trời.', 84, 5, 'PUBLISHED', 50, 'https://images.unsplash.com/photo-1452626038306-9aae5e071dd3'),
        (17, 5, 'Đêm nhạc gây quỹ', 'Sự kiện đã hủy từ trạng thái vé phổ thông.', 91, 3, 'CANCELLED', 45, 'https://images.unsplash.com/photo-1470229722913-7c0e2dbbafd3'),
        (18, 3, 'Vở diễn mùa xuân', 'Sự kiện đã hủy, có sơ đồ ghế và dữ liệu vé để kiểm tra chi tiết.', 98, 3, 'CANCELLED', 50, 'https://images.unsplash.com/photo-1503095396549-807759245b35'),
        (19, 1, 'Lễ hội ánh sáng', 'Sự kiện đã hủy với cả vé ghế ngồi và vé phổ thông.', 105, 7, 'CANCELLED', 55, 'https://images.unsplash.com/photo-1492684223066-81342ee5ff30'),
        (20, 4, 'Giải chạy đêm thành phố', 'Sự kiện đã hủy, dùng vé phổ thông.', 112, 5, 'CANCELLED', 60, 'https://images.unsplash.com/photo-1476480862126-209bfaa8edc8')
)
INSERT INTO events (id, venue_id, name, description, start_at, end_at, sales_start_at, sales_end_at, status, banner_image_url)
SELECT
    ('20000000-0000-4000-8000-' || lpad(event_no::text, 12, '0'))::uuid,
    ('10000000-0000-4000-8000-' || lpad(venue_no::text, 12, '0'))::uuid,
    name,
    description,
    now() + make_interval(days => day_offset),
    now() + make_interval(days => day_offset, hours => duration_hours),
    now() + make_interval(days => day_offset - sales_days_before),
    now() + make_interval(days => day_offset - 1),
    status,
    banner_image_url
FROM event_seed
ON CONFLICT (id) DO NOTHING;

-- TicketType cho từng trạng thái; Event 1 cố ý không có vé để test publish validation.
WITH ticket_seed (ticket_no, event_no, category, name, price, total_quantity) AS (
    VALUES
        (1, 2, 'GENERAL_ADMISSION', 'Vé tham dự', 120.00, 250),
        (2, 3, 'SEATED', 'Khu A', 750.00, 4),
        (3, 4, 'SEATED', 'Hạng thường', 450.00, 8),
        (4, 5, 'SEATED', 'VIP', 900.00, 6),
        (5, 5, 'GENERAL_ADMISSION', 'Vé đứng', 350.00, 120),
        (6, 6, 'GENERAL_ADMISSION', 'Vé tiêu chuẩn', 200.00, 300),
        (7, 6, 'GENERAL_ADMISSION', 'Vé ưu đãi', 150.00, 80),
        (8, 7, 'SEATED', 'Hạng A', 500.00, 10),
        (9, 8, 'GENERAL_ADMISSION', 'Vé vào cửa', 80.00, 500),
        (10, 9, 'GENERAL_ADMISSION', 'Early bird', 250.00, 150),
        (11, 10, 'SEATED', 'Khán đài', 600.00, 12),
        (12, 11, 'SEATED', 'VIP', 1200.00, 6),
        (13, 11, 'GENERAL_ADMISSION', 'Vé đứng', 400.00, 200),
        (14, 12, 'GENERAL_ADMISSION', 'Khách thường', 180.00, 600),
        (15, 12, 'GENERAL_ADMISSION', 'Khách chuyên ngành', 350.00, 120),
        (16, 13, 'SEATED', 'Khu A', 950.00, 10),
        (17, 13, 'SEATED', 'Khu B', 650.00, 8),
        (18, 14, 'GENERAL_ADMISSION', 'Vé miễn phí', 0.00, 800),
        (19, 15, 'SEATED', 'Hạng thường', 320.00, 10),
        (20, 16, 'GENERAL_ADMISSION', 'Vé tham gia', 100.00, 1000),
        (21, 17, 'GENERAL_ADMISSION', 'Vé tham dự', 300.00, 200),
        (22, 18, 'SEATED', 'Hạng A', 550.00, 8),
        (23, 19, 'SEATED', 'VIP', 1000.00, 6),
        (24, 19, 'GENERAL_ADMISSION', 'Vé phổ thông', 250.00, 300),
        (25, 20, 'GENERAL_ADMISSION', 'Vé chạy', 90.00, 1500)
)
INSERT INTO ticket_types (id, event_id, category, name, price, total_quantity)
SELECT
    ('40000000-0000-4000-8000-' || lpad(ticket_no::text, 12, '0'))::uuid,
    ('20000000-0000-4000-8000-' || lpad(event_no::text, 12, '0'))::uuid,
    category,
    name,
    price,
    total_quantity
FROM ticket_seed
ON CONFLICT (id) DO NOTHING;

-- SeatMap cho các event SEATED; mỗi event có đúng một map theo unique(events.event_id).
WITH seat_map_seed (event_no, name) AS (
    VALUES
        (3, 'Sơ đồ chính - Gala âm nhạc biển xanh'),
        (4, 'Sơ đồ chính - Đêm hòa nhạc mùa thu'),
        (5, 'Sơ đồ chính - Lễ hội văn hóa miền Trung'),
        (7, 'Sơ đồ chính - Đêm kịch nói'),
        (10, 'Sơ đồ khán đài - Chung kết e-sports'),
        (11, 'Sơ đồ VIP - Vantix Live'),
        (13, 'Sơ đồ hòa nhạc giao hưởng'),
        (15, 'Sơ đồ - Đêm diễn hài độc thoại'),
        (18, 'Sơ đồ - Vở diễn mùa xuân'),
        (19, 'Sơ đồ VIP - Lễ hội ánh sáng')
)
INSERT INTO seat_maps (id, event_id, name)
SELECT
    ('30000000-0000-4000-8000-' || lpad(event_no::text, 12, '0'))::uuid,
    ('20000000-0000-4000-8000-' || lpad(event_no::text, 12, '0'))::uuid,
    name
FROM seat_map_seed
ON CONFLICT (id) DO NOTHING;

-- Sinh ghế theo từng hàng. Event 3 cố ý để lại 2 ghế chưa gán để kiểm tra lỗi publish.
WITH seat_seed (event_no, section, row_label, seat_count, ticket_no) AS (
    VALUES
        (3, 'A', 'A', 4, 2), (3, 'A', 'B', 2, NULL),
        (4, 'Stalls', 'A', 8, 3),
        (5, 'VIP', 'A', 6, 4),
        (7, 'A', 'A', 10, 8),
        (10, 'Khán đài', 'A', 6, 11), (10, 'Khán đài', 'B', 6, 11),
        (11, 'VIP', 'A', 6, 12),
        (13, 'Khu A', 'A', 10, 16), (13, 'Khu B', 'B', 8, 17),
        (15, 'Stalls', 'A', 10, 19),
        (18, 'A', 'A', 8, 22),
        (19, 'VIP', 'A', 6, 23)
), seats AS (
    SELECT
        event_no, section, row_label, ticket_no, seat_number
    FROM seat_seed
    CROSS JOIN LATERAL generate_series(1, seat_count) AS generated(seat_number)
)
INSERT INTO seats (id, seat_map_id, ticket_type_id, section, row_label, seat_number, display_label, status)
SELECT
    md5(event_no::text || ':' || section || ':' || row_label || ':' || seat_number::text)::uuid,
    ('30000000-0000-4000-8000-' || lpad(event_no::text, 12, '0'))::uuid,
    CASE WHEN ticket_no IS NULL THEN NULL
         ELSE ('40000000-0000-4000-8000-' || lpad(ticket_no::text, 12, '0'))::uuid
    END,
    section,
    row_label,
    lpad(seat_number::text, 2, '0'),
    row_label || '-' || lpad(seat_number::text, 2, '0'),
    'ACTIVE'
FROM seats
ON CONFLICT (id) DO NOTHING;
