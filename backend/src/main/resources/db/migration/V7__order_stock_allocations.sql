-- Append-only allocation evidence associated with each cash checkout.
-- Reservation release requires explicit audited pre-kitchen rejection.
CREATE TABLE order_stock_allocations (
  order_id uuid NOT NULL REFERENCES orders(id),
  menu_item_id uuid NOT NULL REFERENCES menu_items(id),
  quantity integer NOT NULL CHECK (quantity > 0),
  reserved_at timestamptz NOT NULL DEFAULT now(),
  released_at timestamptz,
  PRIMARY KEY(order_id,menu_item_id)
);
CREATE INDEX idx_outstanding_stock_allocations
  ON order_stock_allocations(order_id) WHERE released_at IS NULL;
