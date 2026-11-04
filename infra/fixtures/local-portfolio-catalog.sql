-- SYNTHETIC LOCAL PORTFOLIO DATA. Do not apply to real or shared databases.
-- Only the isolated one-time PowerShell setup script may apply this fixture.
-- No staff accounts, passwords, guest names/phones, payment or receipts.
BEGIN;

INSERT INTO cafe_tables(id,table_code,public_alias,capacity) VALUES
  ('71000000-0000-4000-8000-000000000001','P01','portfolio-01',2),
  ('71000000-0000-4000-8000-000000000002','P02','portfolio-02',4),
  ('71000000-0000-4000-8000-000000000003','P03','portfolio-03',4),
  ('71000000-0000-4000-8000-000000000004','P04','portfolio-04',6),
  ('71000000-0000-4000-8000-000000000005','P05','portfolio-05',2),
  ('71000000-0000-4000-8000-000000000006','P06','portfolio-06',8)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_categories(id,name,slug,sort_order) VALUES
  ('72000000-0000-4000-8000-000000000001','Coffee','portfolio-coffee',1),
  ('72000000-0000-4000-8000-000000000002','Non-Coffee','portfolio-non-coffee',2),
  ('72000000-0000-4000-8000-000000000003','Bites','portfolio-bites',3),
  ('72000000-0000-4000-8000-000000000004','Meals','portfolio-meals',4)
ON CONFLICT (id) DO NOTHING;

INSERT INTO menu_items(id,category_id,name,description,base_price_idr) VALUES
  ('73000000-0000-4000-8000-000000000001','72000000-0000-4000-8000-000000000001',
   'House Espresso','Double-shot espresso with a clean, bold finish.',22000),
  ('73000000-0000-4000-8000-000000000002','72000000-0000-4000-8000-000000000001',
   'Spanish Latte','Espresso and creamy milk with light sweetness.',32000),
  ('73000000-0000-4000-8000-000000000003','72000000-0000-4000-8000-000000000001',
   'Iced Americano','Chilled double-shot coffee with fresh water.',26000),
  ('73000000-0000-4000-8000-000000000004','72000000-0000-4000-8000-000000000001',
   'Caramel Macchiato','Espresso layered with steamed milk and caramel.',35000),
  ('73000000-0000-4000-8000-000000000005','72000000-0000-4000-8000-000000000002',
   'Matcha Latte','Smooth matcha and chilled milk.',34000),
  ('73000000-0000-4000-8000-000000000006','72000000-0000-4000-8000-000000000002',
   'Yuzu Sparkling','Citrus yuzu and sparkling water.',31000),
  ('73000000-0000-4000-8000-000000000007','72000000-0000-4000-8000-000000000002',
   'Chocolate Milk','Rich cocoa with fresh milk.',29000),
  ('73000000-0000-4000-8000-000000000008','72000000-0000-4000-8000-000000000003',
   'Butter Croissant','Golden pastry with a crisp outer layer.',28000),
  ('73000000-0000-4000-8000-000000000009','72000000-0000-4000-8000-000000000003',
   'Truffle Fries','Crisp fries finished with truffle seasoning.',35000),
  ('73000000-0000-4000-8000-000000000010','72000000-0000-4000-8000-000000000003',
   'Crispy Chicken Bites','Bite-sized chicken served with house sauce.',39000),
  ('73000000-0000-4000-8000-000000000011','72000000-0000-4000-8000-000000000004',
   'Chicken Rice Bowl','Seasoned chicken on a bed of warm rice.',45000),
  ('73000000-0000-4000-8000-000000000012','72000000-0000-4000-8000-000000000004',
   'Creamy Mushroom Pasta','Pasta with a savory mushroom cream sauce.',48000)
ON CONFLICT (id) DO NOTHING;

-- Demonstrate a real required variant and an optional price adjustment.
INSERT INTO menu_variant_groups(id,menu_item_id,name,required,active) VALUES
  ('74000000-0000-4000-8000-000000000001','73000000-0000-4000-8000-000000000002','Size',true,true),
  ('74000000-0000-4000-8000-000000000002','73000000-0000-4000-8000-000000000002','Milk',false,true)
ON CONFLICT (id) DO NOTHING;
INSERT INTO menu_variant_options(id,group_id,option_key,label,price_delta_idr) VALUES
  ('75000000-0000-4000-8000-000000000001','74000000-0000-4000-8000-000000000001','regular','Regular',0),
  ('75000000-0000-4000-8000-000000000002','74000000-0000-4000-8000-000000000001','large','Large',6000),
  ('75000000-0000-4000-8000-000000000003','74000000-0000-4000-8000-000000000002','oat','Oat Milk',4000)
ON CONFLICT (id) DO NOTHING;

-- Reproducible synthetic stock with no transaction or real guest involvement.
UPDATE item_availability SET limited_quantity=200,available=true
WHERE menu_item_id IN (
  SELECT id FROM menu_items
  WHERE id::text LIKE '73000000-0000-4000-8000-0000000000%'
);

INSERT INTO physical_games(id,name,description) VALUES
  ('76000000-0000-4000-8000-000000000001','Color Card Deck','Synthetic demonstration card game for cafe loan workflow'),
  ('76000000-0000-4000-8000-000000000002','Classic Checkers','Synthetic physical board-game inventory example')
ON CONFLICT (id) DO NOTHING;
INSERT INTO physical_game_copies(id,game_id,copy_code) VALUES
  ('77000000-0000-4000-8000-000000000001','76000000-0000-4000-8000-000000000001','CARD-DEMO-001'),
  ('77000000-0000-4000-8000-000000000002','76000000-0000-4000-8000-000000000001','CARD-DEMO-002'),
  ('77000000-0000-4000-8000-000000000003','76000000-0000-4000-8000-000000000002','CHECK-DEMO-001')
ON CONFLICT (id) DO NOTHING;
COMMIT;
