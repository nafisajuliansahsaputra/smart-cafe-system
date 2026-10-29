-- GitHub Actions/browser acceptance ONLY. Disposable PostgreSQL database.
-- Do not apply to real cafes, shared staging or existing production data.
-- Role credentials below are synthetic test values, NOT real passwords.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO staff_users (id, login, password_hash) VALUES
  ('11111111-1111-4111-8111-111111111111', 'e2e-waiter',
   crypt('e2e-browser-only-secret-2026', gen_salt('bf', 10))),
  ('22222222-2222-4222-8222-222222222222', 'e2e-cashier',
   crypt('e2e-browser-only-secret-2026', gen_salt('bf', 10))),
  ('33333333-3333-4333-8333-333333333333', 'e2e-kitchen',
   crypt('e2e-browser-only-secret-2026', gen_salt('bf', 10)))
ON CONFLICT (id) DO NOTHING;

INSERT INTO staff_role_assignments (staff_user_id, role_key) VALUES
  ('11111111-1111-4111-8111-111111111111','WAITER'),
  ('22222222-2222-4222-8222-222222222222','CASHIER'),
  ('33333333-3333-4333-8333-333333333333','KITCHEN')
ON CONFLICT DO NOTHING;

INSERT INTO cafe_tables (id, table_code, public_alias, capacity) VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa1111','E2E-01','e2e-01',4)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_categories (id, name, slug, sort_order) VALUES
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbb1111','Browser Acceptance','e2e-browser',1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_items (id,category_id,name,description,base_price_idr) VALUES
  ('cccccccc-cccc-4ccc-8ccc-cccccccc1111',
   'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbb1111',
   'E2E Latte','Synthetic drink for isolated browser acceptance only',28000)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_variant_groups (id,menu_item_id,name,required,active) VALUES
  ('dddddddd-dddd-4ddd-8ddd-dddddddd1111',
   'cccccccc-cccc-4ccc-8ccc-cccccccc1111','Ukuran',true,true),
  ('dddddddd-dddd-4ddd-8ddd-dddddddd2222',
   'cccccccc-cccc-4ccc-8ccc-cccccccc1111','Susu',false,true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_variant_options (id,group_id,option_key,label,price_delta_idr) VALUES
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeee1111',
   'dddddddd-dddd-4ddd-8ddd-dddddddd1111','regular','Regular',0),
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeee2222',
   'dddddddd-dddd-4ddd-8ddd-dddddddd1111','large','Large',5000),
  ('eeeeeeee-eeee-4eee-8eee-eeeeeeee3333',
   'dddddddd-dddd-4ddd-8ddd-dddddddd2222','oat','Oat Milk',2000)
ON CONFLICT (id) DO NOTHING;

-- Quantity is supplied by V6 item insert trigger; set a finite demo inventory.
UPDATE item_availability SET limited_quantity=100, available=true
WHERE menu_item_id='cccccccc-cccc-4ccc-8ccc-cccccccc1111';

-- Synthetic physical game copies for browser-only loan acceptance.
INSERT INTO physical_games(id,name,description) VALUES
  ('ffffffff-ffff-4fff-8fff-ffffffff1111','UNO Demo','Permainan kartu fisik untuk tes browser')
ON CONFLICT (id) DO NOTHING;
INSERT INTO physical_game_copies(id,game_id,copy_code) VALUES
  ('ffffffff-ffff-4fff-8fff-ffffffff2222',
   'ffffffff-ffff-4fff-8fff-ffffffff1111','UNO-E2E-001')
ON CONFLICT (id) DO NOTHING;
