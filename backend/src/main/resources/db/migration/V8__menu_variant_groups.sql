-- M2: one configurable variant group per menu item, at most one option chosen.
-- This deliberately avoids implying support for multiple combination/modifier groups.
CREATE TABLE menu_variant_groups (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  menu_item_id uuid NOT NULL UNIQUE REFERENCES menu_items(id),
  name varchar(80) NOT NULL,
  required boolean NOT NULL DEFAULT false,
  active boolean NOT NULL DEFAULT true,
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0)
);
CREATE TABLE menu_variant_options (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES menu_variant_groups(id),
  option_key varchar(60) NOT NULL,
  label varchar(80) NOT NULL,
  price_delta_idr bigint NOT NULL CHECK(price_delta_idr BETWEEN -1000000000 AND 1000000000),
  active boolean NOT NULL DEFAULT true,
  revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
  UNIQUE(group_id,option_key)
);
ALTER TABLE cart_lines ADD COLUMN variant_option_id uuid REFERENCES menu_variant_options(id);
CREATE INDEX idx_variant_options_group_active ON menu_variant_options(group_id,active);
