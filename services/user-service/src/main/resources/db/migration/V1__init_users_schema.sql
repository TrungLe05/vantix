CREATE TYPE user_role AS ENUM ('BUYER', 'ADMIN', 'STAFF');
CREATE TYPE user_status AS ENUM ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED');
CREATE TYPE oauth_provider AS ENUM ('NONE', 'GOOGLE');

CREATE TABLE users (
                       id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       email                  VARCHAR(255) NOT NULL,
                       password_hash          VARCHAR(255),
                       full_name              VARCHAR(255) NOT NULL,
                       role                   user_role NOT NULL DEFAULT 'BUYER',
                       status                 user_status NOT NULL DEFAULT 'PENDING_VERIFICATION',
                       oauth_provider         oauth_provider NOT NULL DEFAULT 'NONE',
                       oauth_id               VARCHAR(255),
                       must_change_password   BOOLEAN NOT NULL DEFAULT FALSE,
                       created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
                       updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
                       deleted_at             TIMESTAMPTZ
);

-- Chỉ unique khi chưa bị xóa mềm — cho phép tạo lại email đã xóa
CREATE UNIQUE INDEX idx_users_email ON users (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX idx_users_oauth ON users (oauth_provider, oauth_id) WHERE oauth_provider != 'NONE';

CREATE TABLE refresh_tokens (
                                id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                family_id                 UUID NOT NULL,
                                user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                token_hash                VARCHAR(255) NOT NULL,
                                device_fingerprint_hash   VARCHAR(64),
                                created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
                                expires_at                TIMESTAMPTZ NOT NULL,
                                revoked_at                TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE UNIQUE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);