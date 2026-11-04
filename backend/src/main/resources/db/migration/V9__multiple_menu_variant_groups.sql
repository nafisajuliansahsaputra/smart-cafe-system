-- Multiple independently required/optional, single-select modifier groups per menu item.
-- Keep old option column for additive migration compatibility; normalized junction is authoritative.
ALTER TABLE menu_variant_groups DROP CONSTRAINT IF EXISTS menu_variant_groups_menu_item_id_key;
CREATE UNIQUE INDEX uq_variant_group_item_name
    ON menu_variant_groups(menu_item_id, lower(name));
CREATE TABLE cart_line_variant_options (
    cart_id uuid NOT NULL,
    menu_item_id uuid NOT NULL,
    option_id uuid NOT NULL REFERENCES menu_variant_options(id),
    PRIMARY KEY(cart_id,menu_item_id,option_id),
    FOREIGN KEY(cart_id,menu_item_id)
        REFERENCES cart_lines(cart_id,menu_item_id) ON DELETE CASCADE
);
INSERT INTO cart_line_variant_options(cart_id,menu_item_id,option_id)
SELECT cart_id,menu_item_id,variant_option_id
FROM cart_lines WHERE variant_option_id IS NOT NULL
ON CONFLICT DO NOTHING;
CREATE INDEX idx_cart_line_option_lookup ON cart_line_variant_options(option_id);
