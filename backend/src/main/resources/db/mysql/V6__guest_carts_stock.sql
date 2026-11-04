-- MySQL 8: protected menu stock and per-visit cart.
CREATE TABLE item_availability (
  menu_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  limited_quantity BIGINT CHECK (limited_quantity IS NULL OR limited_quantity >= 0),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  FOREIGN KEY (menu_item_id) REFERENCES menu_items(id)
);
INSERT INTO item_availability(menu_item_id) SELECT id FROM menu_items;
CREATE TRIGGER menu_availability_insert
  AFTER INSERT ON menu_items FOR EACH ROW
  INSERT INTO item_availability(menu_item_id) VALUES (NEW.id);

CREATE TABLE carts (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  visit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  FOREIGN KEY (guest_id,visit_id) REFERENCES visit_guests(id,visit_id)
);
CREATE TABLE cart_lines (
  cart_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  menu_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 20),
  PRIMARY KEY (cart_id,menu_item_id),
  FOREIGN KEY (cart_id) REFERENCES carts(id) ON DELETE CASCADE,
  FOREIGN KEY (menu_item_id) REFERENCES menu_items(id)
);
CREATE INDEX idx_carts_visit ON carts(visit_id);
