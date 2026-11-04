-- MySQL 8: allocation evidence. A released allocation is still retained.
CREATE TABLE order_stock_allocations (
  order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  menu_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  reserved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  released_at DATETIME(6),
  PRIMARY KEY (order_id,menu_item_id),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  FOREIGN KEY (menu_item_id) REFERENCES menu_items(id)
);
-- MySQL has no partial indexes; NULL-first/lookup plan is checked in E2E.
CREATE INDEX idx_outstanding_stock_allocations
  ON order_stock_allocations(released_at,order_id);
