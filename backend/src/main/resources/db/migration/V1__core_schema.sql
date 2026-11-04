-- M1 baseline: foundational M2 tables and collision-safe database invariants.
-- No production staff, payments, or real customers are seeded.
CREATE TABLE staff_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    login varchar(120) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE staff_roles (
    role_key varchar(32) PRIMARY KEY CHECK (role_key IN ('WAITER','KITCHEN','CASHIER','MANAGER','ADMIN'))
);
INSERT INTO staff_roles(role_key) VALUES ('WAITER'),('KITCHEN'),('CASHIER'),('MANAGER'),('ADMIN');
CREATE TABLE staff_role_assignments (
    staff_user_id uuid NOT NULL REFERENCES staff_users(id),
    role_key varchar(32) NOT NULL REFERENCES staff_roles(role_key),
    PRIMARY KEY (staff_user_id,role_key)
);
CREATE TABLE cafe_tables (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    table_code varchar(40) NOT NULL UNIQUE,
    public_alias varchar(100) NOT NULL UNIQUE,
    capacity integer NOT NULL CHECK (capacity > 0),
    status varchar(20) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN ('AVAILABLE','OUT_OF_SERVICE')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE table_visits (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    table_id uuid NOT NULL REFERENCES cafe_tables(id),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','CLOSING','CLOSED','VOIDED')),
    opened_by uuid NOT NULL REFERENCES staff_users(id),
    join_secret_hash char(64) NOT NULL,
    rotation_version integer NOT NULL DEFAULT 1 CHECK (rotation_version > 0),
    opened_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT closed_visit_requires_time CHECK (status NOT IN ('CLOSED','VOIDED') OR closed_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_active_table_visit ON table_visits(table_id)
    WHERE status IN ('ACTIVE','CLOSING');
CREATE TABLE visit_guests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id uuid NOT NULL REFERENCES table_visits(id),
    display_name varchar(80) NOT NULL CHECK (char_length(trim(display_name)) > 0),
    phone_e164_encrypted bytea,
    phone_last4 char(4),
    receipt_opt_in_at timestamptz,
    receipt_channel_preference varchar(20),
    consent_notice_version varchar(40),
    joined_at timestamptz NOT NULL DEFAULT now(),
    credential_version integer NOT NULL DEFAULT 1 CHECK (credential_version > 0),
    UNIQUE (id, visit_id),
    CONSTRAINT consent_requires_channel CHECK (receipt_opt_in_at IS NULL OR receipt_channel_preference IS NOT NULL)
);
CREATE TABLE menu_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(120) NOT NULL,
    slug varchar(120) NOT NULL UNIQUE,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true
);
CREATE TABLE menu_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id uuid NOT NULL REFERENCES menu_categories(id),
    name varchar(160) NOT NULL,
    description text,
    base_price_idr bigint NOT NULL CHECK (base_price_idr >= 0),
    availability varchar(20) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (availability IN ('AVAILABLE','SOLD_OUT','HIDDEN')),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0)
);
CREATE TABLE orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_number bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    visit_id uuid NOT NULL REFERENCES table_visits(id),
    guest_id uuid NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED','ACCEPTED','PREPARING','READY','SERVED','CANCELLED','REJECTED')),
    currency char(3) NOT NULL DEFAULT 'IDR' CHECK (currency = 'IDR'),
    subtotal_idr bigint NOT NULL CHECK (subtotal_idr >= 0),
    discount_idr bigint NOT NULL DEFAULT 0 CHECK (discount_idr >= 0),
    tax_idr bigint NOT NULL DEFAULT 0 CHECK (tax_idr >= 0),
    service_fee_idr bigint NOT NULL DEFAULT 0 CHECK (service_fee_idr >= 0),
    total_idr bigint NOT NULL CHECK (total_idr >= 0),
    pricing_policy_version varchar(50) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    accepted_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (guest_id,visit_id) REFERENCES visit_guests(id,visit_id)
);
CREATE INDEX idx_orders_visit_created ON orders(visit_id,created_at DESC);
CREATE INDEX idx_orders_status_created ON orders(status,created_at DESC);
CREATE TABLE order_lines (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES orders(id),
    menu_item_id uuid REFERENCES menu_items(id),
    name_snapshot varchar(160) NOT NULL,
    variant_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    unit_price_idr bigint NOT NULL CHECK (unit_price_idr >= 0),
    quantity integer NOT NULL CHECK (quantity > 0),
    line_total_idr bigint NOT NULL CHECK (line_total_idr >= 0),
    notes text
);
CREATE TABLE order_idempotency (
    actor_id uuid NOT NULL REFERENCES visit_guests(id),
    scope varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id,scope,idempotency_key)
);
CREATE TABLE payment_intents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
    method_selected varchar(20) NOT NULL CHECK (method_selected IN ('CASH','QRIS','VA')),
    amount_due_idr bigint NOT NULL CHECK (amount_due_idr >= 0),
    amount_paid_idr bigint NOT NULL DEFAULT 0 CHECK (amount_paid_idr >= 0),
    aggregate_status varchar(24) NOT NULL CHECK (aggregate_status IN
        ('UNPAID','AWAITING_CASH','PENDING','PAID','PARTIALLY_REFUNDED','REFUNDED','DISPUTED')),
    currency char(3) NOT NULL DEFAULT 'IDR' CHECK (currency = 'IDR'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE kitchen_tickets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
    status varchar(20) NOT NULL CHECK (status IN ('ACCEPTED','PREPARING','READY')),
    admitted_at timestamptz NOT NULL DEFAULT now(),
    preparing_at timestamptz,
    ready_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE cash_tenders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_intent_id uuid NOT NULL REFERENCES payment_intents(id),
    staff_user_id uuid NOT NULL REFERENCES staff_users(id),
    amount_received_idr bigint NOT NULL CHECK (amount_received_idr >= 0),
    change_given_idr bigint NOT NULL CHECK (change_given_idr >= 0),
    idempotency_key varchar(128) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (payment_intent_id,idempotency_key)
);
CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type varchar(30) NOT NULL,
    actor_id uuid,
    operation varchar(120) NOT NULL,
    resource_type varchar(80) NOT NULL,
    resource_id uuid NOT NULL,
    reason text,
    correlation_id varchar(100) NOT NULL,
    safe_before_after_diff jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_resource ON audit_logs(resource_type,resource_id,occurred_at);
CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    payload_safe jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    locked_until timestamptz,
    processed_at timestamptz,
    retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count >= 0)
);
CREATE INDEX idx_outbox_unprocessed ON outbox_events(created_at) WHERE processed_at IS NULL;
