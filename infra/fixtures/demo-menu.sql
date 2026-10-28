-- Optional synthetic DEV fixture. Apply manually to a disposable local database only.
-- No guest phone, staff password or real payment data.
INSERT INTO cafe_tables(id,table_code,public_alias,capacity)
VALUES
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0001','01','demo-01',2),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0002','02','demo-02',4),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa0007','07','demo-07',4)
ON CONFLICT (table_code) DO NOTHING;

INSERT INTO menu_categories(id,name,slug)
VALUES ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','Demo Drinks','demo-drinks')
ON CONFLICT (slug) DO NOTHING;

INSERT INTO menu_items(id,category_id,name,base_price_idr)
VALUES ('cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','Demo Latte',28000)
ON CONFLICT (id) DO NOTHING;
