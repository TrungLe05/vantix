CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_id UUID NOT NULL,
    event_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN (
        'CART', 'PENDING_PAYMENT', 'PAID', 'TICKETS_ISSUED', 'CANCELLED', 'EXPIRED'
    )),
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    hold_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_orders_buyer_event_cart
    ON orders (buyer_id, event_id)
    WHERE status = 'CART';

CREATE INDEX idx_orders_buyer_status_created
    ON orders (buyer_id, status, created_at DESC);

CREATE INDEX idx_orders_expiring
    ON orders (hold_expires_at)
    WHERE status IN ('CART', 'PENDING_PAYMENT');

CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    ticket_type_id UUID NOT NULL,
    seat_id UUID,
    seat_label_snapshot VARCHAR(120),
    ticket_name_snapshot VARCHAR(200) NOT NULL,
    category_snapshot VARCHAR(30) NOT NULL CHECK (category_snapshot IN ('SEATED', 'GENERAL_ADMISSION')),
    unit_price_snapshot NUMERIC(10, 2) NOT NULL CHECK (unit_price_snapshot >= 0),
    quantity INT NOT NULL CHECK (quantity > 0),
    item_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (item_status IN ('ACTIVE', 'REMOVED', 'EXPIRED', 'CANCELLED')),
    subtotal NUMERIC(12, 2) NOT NULL CHECK (subtotal >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (category_snapshot = 'SEATED' AND seat_id IS NOT NULL AND quantity = 1)
        OR (category_snapshot = 'GENERAL_ADMISSION' AND seat_id IS NULL)
    )
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_ticket_type_id ON order_items (ticket_type_id);

CREATE TABLE holds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL REFERENCES order_items (id) ON DELETE CASCADE,
    ticket_type_id UUID NOT NULL,
    seat_id UUID,
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'PURCHASED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_holds_active_seat
    ON holds (seat_id)
    WHERE seat_id IS NOT NULL AND status IN ('HELD', 'CONFIRMED', 'PURCHASED');

CREATE INDEX idx_holds_expiration ON holds (expires_at) WHERE status IN ('HELD', 'CONFIRMED');
CREATE INDEX idx_holds_order_id ON holds (order_id);

CREATE TABLE ticket_inventory (
    ticket_type_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    total_quantity INT NOT NULL CHECK (total_quantity >= 0),
    held_quantity INT NOT NULL DEFAULT 0 CHECK (held_quantity >= 0),
    sold_quantity INT NOT NULL DEFAULT 0 CHECK (sold_quantity >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (held_quantity + sold_quantity <= total_quantity)
);

CREATE INDEX idx_ticket_inventory_event_id ON ticket_inventory (event_id);

CREATE TABLE cancelled_events (
    event_id UUID PRIMARY KEY,
    cancelled_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_id UUID NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_buyer_operation_key UNIQUE (buyer_id, operation, idempotency_key)
);

CREATE TABLE order_event_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    event_id UUID NOT NULL,
    item_count INT NOT NULL CHECK (item_count > 0),
    quantity INT NOT NULL CHECK (quantity > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_event_outbox_pending
    ON order_event_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
