-- Limited stock row exists for each menu item, generated on insertion.
CREATE TABLE item_availability (
  menu_item_id uuid PRIMARY KEY REFERENCES menu_items(id),
  available boolean NOT NULL DEFAULT true,
  limited_quantity bigint CHECK (limited_quantity IS NULL OR limited_quantity >= 0),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
INSERT INTO item_availability(menu_item_id) SELECT id FROM menu_items;
CREATE FUNCTION create_item_availability() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO item_availability(menu_item_id) VALUES (NEW.id);
  RETURN NEW;
END; $$;
CREATE TRIGGER menu_availability_insert AFTER INSERT ON menu_items
  FOR EACH ROW EXECUTE FUNCTION create_item_availability();

CREATE TABLE carts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  guest_id uuid NOT NULL UNIQUE,
  visit_id uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0 CHECK (version>=0),
  FOREIGN KEY (guest_id,visit_id) REFERENCES visit_guests(id,visit_id)
);
CREATE TABLE cart_lines (
  cart_id uuid NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
  menu_item_id uuid NOT NULL REFERENCES menu_items(id),
  quantity integer NOT NULL CHECK(quantity BETWEEN 1 AND 20),
  PRIMARY KEY(cart_id,menu_item_id)
);
CREATE INDEX idx_carts_visit ON carts(visit_id);
