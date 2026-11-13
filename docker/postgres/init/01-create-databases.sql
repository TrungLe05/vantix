-- Tạo database riêng cho từng service (database-per-service, D02).
-- Image postgres chỉ chạy thư mục /docker-entrypoint-initdb.d khi volume dữ liệu còn trống,
-- nên script viết idempotent (\gexec + NOT EXISTS) để chạy tay lại được trên volume cũ:
--   docker exec -i postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres' < docker/postgres/init/01-create-databases.sql
-- Tên DB phải khớp spring.datasource.url trong application.yaml của từng service.

SELECT format('CREATE DATABASE %I', db)
FROM unnest(ARRAY[
    'users_service',
    'products_service',
    'orders_service',
    'payments_service',
    'notifications_service'
]) AS db
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = db)
\gexec
