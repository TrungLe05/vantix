-- DB riêng cho integration test (D21), tách khỏi DB dev để test không đụng dữ liệu đang dùng.
-- Chạy bằng superuser, idempotent:
--   local: docker exec -i postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres' < docker/postgres/test/create-test-databases.sql
--   CI   : psql -h localhost -U vantix_test -d postgres -f docker/postgres/test/create-test-databases.sql
-- Role vantix_test chỉ dùng cho test, mật khẩu cố định không phải secret.

SELECT 'CREATE ROLE vantix_test LOGIN PASSWORD ''vantix_test'''
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'vantix_test')
\gexec

SELECT format('CREATE DATABASE %I OWNER vantix_test', db)
FROM unnest(ARRAY[
    'users_service_test',
    'products_service_test',
    'orders_service_test'
]) AS db
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = db)
\gexec
