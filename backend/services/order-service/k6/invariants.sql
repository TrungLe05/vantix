-- Kiểm tra bất biến sau khi chạy k6 (chạy trên DB orders_service, chỉ đọc).
--   docker exec -i postgres sh -c 'psql -U "$POSTGRES_USER" -d orders_service -v event_id="'<EVENT_ID>'"' < invariants.sql
-- Mọi truy vấn "vi phạm" phải trả 0 dòng.

\echo '1) Ghế có hơn một hold hiệu lực (phải 0 dòng)'
SELECT seat_id, count(*) AS active_holds
FROM holds
WHERE seat_id IS NOT NULL AND status IN ('HELD', 'CONFIRMED', 'PURCHASED')
GROUP BY seat_id HAVING count(*) > 1;

\echo '2) GA vượt sức chứa hoặc held lệch tổng hold (phải 0 dòng)'
SELECT i.ticket_type_id, i.total_quantity, i.held_quantity, i.sold_quantity,
       COALESCE(sum(h.quantity) FILTER (WHERE h.status IN ('HELD', 'CONFIRMED')), 0) AS active_hold_qty
FROM ticket_inventory i
LEFT JOIN holds h ON h.ticket_type_id = i.ticket_type_id
WHERE i.event_id = :'event_id'
GROUP BY i.ticket_type_id, i.total_quantity, i.held_quantity, i.sold_quantity
HAVING i.held_quantity + i.sold_quantity > i.total_quantity
    OR i.held_quantity <> COALESCE(sum(h.quantity) FILTER (WHERE h.status IN ('HELD', 'CONFIRMED')), 0);

\echo '3) Order đã CANCELLED/EXPIRED nhưng còn hold hiệu lực — tồn bị giữ "mồ côi" (phải 0 dòng)'
SELECT o.id AS order_id, o.status AS order_status, h.id AS hold_id, h.status AS hold_status
FROM orders o JOIN holds h ON h.order_id = o.id
WHERE o.event_id = :'event_id'
  AND o.status IN ('CANCELLED', 'EXPIRED') AND h.status IN ('HELD', 'CONFIRMED');

\echo '4) Mỗi buyer tối đa một CART cho event (phải 0 dòng)'
SELECT buyer_id, count(*) FROM orders
WHERE event_id = :'event_id' AND status = 'CART'
GROUP BY buyer_id HAVING count(*) > 1;

\echo '5) Expiry lag: thời gian từ deadline tới lúc hold được chuyển EXPIRED (giây)'
SELECT count(*) AS expired_holds,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY lag)::numeric, 3) AS p50_s,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY lag)::numeric, 3) AS p95_s,
       round(max(lag)::numeric, 3) AS max_s
FROM (
    SELECT EXTRACT(EPOCH FROM (h.updated_at - h.expires_at)) AS lag
    FROM holds h JOIN orders o ON o.id = h.order_id
    WHERE o.event_id = :'event_id' AND h.status = 'EXPIRED'
) t;
