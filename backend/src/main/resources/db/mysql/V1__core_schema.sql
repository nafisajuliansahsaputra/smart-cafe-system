-- MySQL 8.0.16+ PORT IN PROGRESS: M1 core only. Do not deploy as operational POS.
-- Only new disposable databases. Preserve PG production migration history unchanged.
-- All UUIDs are wire-compatible ASCII 36-byte strings (driver adaptation pending).
-- Time values are UTC DATETIME(6); deployments must set session time_zone='+00:00'.
CREATE TABLE staff_users (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    login varchar(120) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE staff_roles (
    role_key varchar(32) COLLATE utf8mb4_bin PRIMARY KEY CHECK (role_key IN ('WAITER','KITCHEN','CASHIER','MANAGER','ADMIN'))
);
INSERT INTO staff_roles(role_key) VALUES ('WAITER'),('KITCHEN'),('CASHIER'),('MANAGER'),('ADMIN');
CREATE TABLE staff_role_assignments (
    staff_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_key varchar(32) COLLATE utf8mb4_bin NOT NULL REFERENCES staff_roles(role_key),
    PRIMARY KEY (staff_user_id,role_key),
    FOREIGN KEY (staff_user_id) REFERENCES staff_users(id)
);
CREATE TABLE cafe_tables (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    table_code varchar(40) NOT NULL UNIQUE,
    public_alias varchar(100) NOT NULL UNIQUE,
    capacity integer NOT NULL CHECK (capacity > 0),
    status varchar(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE','OUT_OF_SERVICE')),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE table_visits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    table_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status varchar(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','CLOSING','CLOSED','VOIDED')),
    opened_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    join_secret_hash char(64) NOT NULL,
    rotation_version integer NOT NULL DEFAULT 1 CHECK (rotation_version > 0),
    opened_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at DATETIME(6),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT closed_visit_requires_time CHECK (status NOT IN ('CLOSED','VOIDED') OR closed_at IS NOT NULL),
    -- MySQL replacement for partial unique uq_active_table_visit:
    -- exactly one ACTIVE/CLOSING visit; NULL is never unique-constrained.
    active_table_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
      GENERATED ALWAYS AS (CASE WHEN status IN ('ACTIVE','CLOSING') THEN table_id ELSE NULL END) STORED,
    UNIQUE KEY uq_active_table_visit (active_table_key),
    FOREIGN KEY (table_id) REFERENCES cafe_tables(id),
    FOREIGN KEY (opened_by) REFERENCES staff_users(id)
);

CREATE TABLE visit_guests (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    visit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_name varchar(80) NOT NULL CHECK (char_length(trim(display_name)) > 0),
    phone_e164_encrypted VARBINARY(255),
    phone_last4 char(4),
    receipt_opt_in_at DATETIME(6),
    receipt_channel_preference varchar(20),
    consent_notice_version varchar(40),
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    credential_version integer NOT NULL DEFAULT 1 CHECK (credential_version > 0),
    UNIQUE (id, visit_id),
    CONSTRAINT consent_requires_channel CHECK (receipt_opt_in_at IS NULL OR receipt_channel_preference IS NOT NULL),
    FOREIGN KEY (visit_id) REFERENCES table_visits(id)
);
CREATE TABLE menu_categories (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    name varchar(120) NOT NULL,
    slug varchar(120) NOT NULL UNIQUE,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true
);
CREATE TABLE menu_items (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    category_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name varchar(160) NOT NULL,
    description text,
    base_price_idr bigint NOT NULL CHECK (base_price_idr >= 0),
    availability varchar(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'AVAILABLE'
        CHECK (availability IN ('AVAILABLE','SOLD_OUT','HIDDEN')),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    FOREIGN KEY (category_id) REFERENCES menu_categories(id)
);
CREATE TABLE orders (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    display_number BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
    visit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status varchar(20) COLLATE utf8mb4_bin NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED','ACCEPTED','PREPARING','READY','SERVED','CANCELLED','REJECTED')),
    currency char(3) COLLATE utf8mb4_bin NOT NULL DEFAULT 'IDR' CHECK (currency = 'IDR'),
    subtotal_idr bigint NOT NULL CHECK (subtotal_idr >= 0),
    discount_idr bigint NOT NULL DEFAULT 0 CHECK (discount_idr >= 0),
    tax_idr bigint NOT NULL DEFAULT 0 CHECK (tax_idr >= 0),
    service_fee_idr bigint NOT NULL DEFAULT 0 CHECK (service_fee_idr >= 0),
    total_idr bigint NOT NULL CHECK (total_idr >= 0),
    pricing_policy_version varchar(50) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    accepted_at DATETIME(6),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (guest_id,visit_id) REFERENCES visit_guests(id,visit_id),
    FOREIGN KEY (visit_id) REFERENCES table_visits(id)
);
CREATE INDEX idx_orders_visit_created ON orders(visit_id,created_at DESC);
CREATE INDEX idx_orders_status_created ON orders(status,created_at DESC);
CREATE TABLE order_lines (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    menu_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    name_snapshot varchar(160) NOT NULL,
    variant_snapshot JSON NOT NULL DEFAULT (JSON_OBJECT()),
    unit_price_idr bigint NOT NULL CHECK (unit_price_idr >= 0),
    quantity integer NOT NULL CHECK (quantity > 0),
    line_total_idr bigint NOT NULL CHECK (line_total_idr >= 0),
    notes text,
    FOREIGN KEY (order_id) REFERENCES orders(id),
    FOREIGN KEY (menu_item_id) REFERENCES menu_items(id)
);
CREATE TABLE order_idempotency (
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (actor_id,scope,idempotency_key),
    FOREIGN KEY (actor_id) REFERENCES visit_guests(id),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE payment_intents (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    method_selected varchar(20) COLLATE utf8mb4_bin NOT NULL CHECK (method_selected IN ('CASH','QRIS','VA')),
    amount_due_idr bigint NOT NULL CHECK (amount_due_idr >= 0),
    amount_paid_idr bigint NOT NULL DEFAULT 0 CHECK (amount_paid_idr >= 0),
    aggregate_status varchar(24) COLLATE utf8mb4_bin NOT NULL CHECK (aggregate_status IN
        ('UNPAID','AWAITING_CASH','PENDING','PAID','PARTIALLY_REFUNDED','REFUNDED','DISPUTED')),
    currency char(3) COLLATE utf8mb4_bin NOT NULL DEFAULT 'IDR' CHECK (currency = 'IDR'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE kitchen_tickets (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    status varchar(20) COLLATE utf8mb4_bin NOT NULL CHECK (status IN ('ACCEPTED','PREPARING','READY')),
    admitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    preparing_at DATETIME(6),
    ready_at DATETIME(6),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE TABLE cash_tenders (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    payment_intent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    staff_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_received_idr bigint NOT NULL CHECK (amount_received_idr >= 0),
    change_given_idr bigint NOT NULL CHECK (change_given_idr >= 0),
    idempotency_key varchar(128) NOT NULL,
    recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (payment_intent_id,idempotency_key),
    FOREIGN KEY (payment_intent_id) REFERENCES payment_intents(id),
    FOREIGN KEY (staff_user_id) REFERENCES staff_users(id)
);
CREATE TABLE audit_logs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    actor_type varchar(30) NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    operation varchar(120) NOT NULL,
    resource_type varchar(80) NOT NULL,
    resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason text,
    correlation_id varchar(100) NOT NULL,
    safe_before_after_diff JSON,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_audit_resource ON audit_logs(resource_type,resource_id,occurred_at);
CREATE TABLE outbox_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type varchar(120) NOT NULL,
    payload_safe JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    locked_until DATETIME(6),
    processed_at DATETIME(6),
    retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count >= 0)
);
CREATE INDEX idx_outbox_unprocessed ON outbox_events(processed_at,created_at);
