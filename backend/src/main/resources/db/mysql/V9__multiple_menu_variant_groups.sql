-- MySQL 8: multiple independently required/optional, single-select
-- modifier groups. MySQL's case-insensitive default name collation provides
-- uniqueness of (item,name) after dropping the legacy per-item constraint.
CREATE UNIQUE INDEX uq_variant_group_item_name
  ON menu_variant_groups(menu_item_id,name);
ALTER TABLE menu_variant_groups DROP INDEX menu_item_id;

CREATE TABLE cart_line_variant_options (
  cart_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  menu_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  option_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (cart_id,menu_item_id,option_id),
  FOREIGN KEY (option_id) REFERENCES menu_variant_options(id),
  FOREIGN KEY (cart_id,menu_item_id)
    REFERENCES cart_lines(cart_id,menu_item_id) ON DELETE CASCADE
);
INSERT INTO cart_line_variant_options(cart_id,menu_item_id,option_id)
SELECT cart_id,menu_item_id,variant_option_id
FROM cart_lines WHERE variant_option_id IS NOT NULL;
CREATE INDEX idx_cart_line_option_lookup ON cart_line_variant_options(option_id);
