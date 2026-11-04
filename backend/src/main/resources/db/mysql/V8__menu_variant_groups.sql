-- MySQL 8: first variant group per item, normalized option pricing.
CREATE TABLE menu_variant_groups (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  menu_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL,
  required BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
  FOREIGN KEY (menu_item_id) REFERENCES menu_items(id)
);
CREATE TABLE menu_variant_options (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY DEFAULT (UUID()),
  group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  option_key VARCHAR(60) NOT NULL,
  label VARCHAR(80) NOT NULL,
  price_delta_idr BIGINT NOT NULL
    CHECK (price_delta_idr BETWEEN -1000000000 AND 1000000000),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
  UNIQUE (group_id,option_key),
  FOREIGN KEY (group_id) REFERENCES menu_variant_groups(id)
);
ALTER TABLE cart_lines
  ADD COLUMN variant_option_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
  ADD CONSTRAINT fk_cart_line_variant_option
    FOREIGN KEY (variant_option_id) REFERENCES menu_variant_options(id);
CREATE INDEX idx_variant_options_group_active ON menu_variant_options(group_id,active);
