CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE venues (
                        id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        name       VARCHAR(200) NOT NULL,
                        address    VARCHAR(255) NOT NULL,
                        city       VARCHAR(100) NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE events (
                        id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        venue_id          UUID NOT NULL REFERENCES venues (id),
                        name              VARCHAR(200) NOT NULL,
                        description       TEXT,
                        start_at          TIMESTAMPTZ NOT NULL,
                        end_at            TIMESTAMPTZ NOT NULL,
                        sales_start_at    TIMESTAMPTZ,
                        sales_end_at      TIMESTAMPTZ,
                        status            VARCHAR(20) NOT NULL,   -- DRAFT | PUBLISHED | CANCELLED
                        banner_image_url  VARCHAR(500),
                        created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_status_start_at ON events (status, start_at);
CREATE INDEX idx_events_venue_id ON events (venue_id);

CREATE TABLE seat_maps (
                           id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           event_id   UUID NOT NULL UNIQUE REFERENCES events (id),  -- 1-1 với Event
                           name       VARCHAR(200) NOT NULL,
                           created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ticket_types (
                              id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              event_id       UUID NOT NULL REFERENCES events (id),
                              category       VARCHAR(30) NOT NULL,   -- SEATED | GENERAL_ADMISSION
                              name           VARCHAR(200) NOT NULL,
                              price          NUMERIC(10, 2) NOT NULL,
                              total_quantity INT NOT NULL DEFAULT 0,  -- GA: nhập tay; SEATED: dẫn xuất = COUNT(seats)
                              created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                              updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ticket_types_event_id ON ticket_types (event_id);

CREATE TABLE seats (
                       id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       seat_map_id     UUID NOT NULL REFERENCES seat_maps (id),
                       ticket_type_id  UUID REFERENCES ticket_types (id),  -- nullable: chưa gán giá lúc mới tạo ghế
                       section         VARCHAR(50) NOT NULL,
                       row_label       VARCHAR(20) NOT NULL,
                       seat_number     VARCHAR(20) NOT NULL,
                       display_label   VARCHAR(50) NOT NULL,
                       status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'  -- ACTIVE | INACTIVE — trạng thái CATALOG, không phải trạng thái bán
);

-- Lớp chặn cuối chống trùng ghế, kể cả khi có race condition ở tầng service
CREATE UNIQUE INDEX uk_seats_map_section_row_number
    ON seats (seat_map_id, section, row_label, seat_number);

CREATE INDEX idx_seats_ticket_type_id ON seats (ticket_type_id);