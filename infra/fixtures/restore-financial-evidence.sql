-- CI ONLY: synthetic money/receipt/custody evidence for PostgreSQL archive restore.
-- Must follow e2e-browser.sql on a new disposable database at Flyway V30.
-- Never execute on a real cafe, shared environment, or merchant database.
-- Fixed IDs allow safe negative assertions AFTER restore; not reusable live data.
DO $probe$
BEGIN
  IF current_database() <> 'smart_cafe'
     OR NOT EXISTS(SELECT 1 FROM staff_users
                   WHERE id='44444444-4444-4444-8444-444444444444'
                     AND login='e2e-manager')
     OR NOT EXISTS(SELECT 1 FROM staff_users
                   WHERE id='11111111-1111-4111-8111-111111111111'
                     AND login='e2e-waiter')
     OR NOT EXISTS(SELECT 1 FROM cafe_tables
                   WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa1111'
                     AND table_code='E2E-01')
     OR NOT EXISTS(SELECT 1 FROM flyway_schema_history
                   WHERE version='30' AND success) THEN
    RAISE EXCEPTION 'REFUSED: synthetic CI-only V30 restore prerequisites missing';
  END IF;
END $probe$;

INSERT INTO table_visits(id,table_id,opened_by,join_secret_hash)
VALUES ('88888888-8888-4888-8888-888888888801',
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaa1111',
        '11111111-1111-4111-8111-111111111111',
        repeat('e',64));
INSERT INTO visit_guests(id,visit_id,display_name)
VALUES ('88888888-8888-4888-8888-888888888802',
        '88888888-8888-4888-8888-888888888801','Restore Fixture Guest');
INSERT INTO orders(id,visit_id,guest_id,status,subtotal_idr,total_idr,
                   pricing_policy_version)
VALUES ('88888888-8888-4888-8888-888888888803',
        '88888888-8888-4888-8888-888888888801',
        '88888888-8888-4888-8888-888888888802',
        'SERVED',35000,35000,'RESTORE-CI-SYNTHETIC');
INSERT INTO order_lines(id,order_id,menu_item_id,name_snapshot,
                        unit_price_idr,quantity,line_total_idr)
VALUES ('88888888-8888-4888-8888-888888888804',
        '88888888-8888-4888-8888-888888888803',
        'cccccccc-cccc-4ccc-8ccc-cccccccc1111',
        'Restore Fixture Latte',35000,1,35000);
INSERT INTO payment_intents(id,order_id,method_selected,amount_due_idr,
                            amount_paid_idr,aggregate_status)
VALUES ('88888888-8888-4888-8888-888888888805',
        '88888888-8888-4888-8888-888888888803',
        'CASH',35000,35000,'PAID');

INSERT INTO cash_drawer_shifts(id,opened_by,opening_float_idr,open_key)
VALUES ('88888888-8888-4888-8888-888888888806',
        '44444444-4444-4444-8444-444444444444',10000,
        'ci-restore-shift-open-unique');
INSERT INTO cash_tenders(id,payment_intent_id,staff_user_id,amount_received_idr,
                         change_given_idr,idempotency_key,cash_shift_id)
VALUES ('88888888-8888-4888-8888-888888888807',
        '88888888-8888-4888-8888-888888888805',
        '11111111-1111-4111-8111-111111111111',
        50000,15000,'ci-restore-cash-tender-unique',
        '88888888-8888-4888-8888-888888888806');
INSERT INTO cash_tender_handoffs(tender_id,cash_shift_id,waiter_staff_id)
VALUES ('88888888-8888-4888-8888-888888888807',
        '88888888-8888-4888-8888-888888888806',
        '11111111-1111-4111-8111-111111111111');
UPDATE cash_tender_handoffs
SET received_by='44444444-4444-4444-8444-444444444444',
    received_at=clock_timestamp(),
    acknowledgment_key='ci-restore-physical-receipt'
WHERE tender_id='88888888-8888-4888-8888-888888888807';

INSERT INTO receipts(id,payment_intent_id,order_id,total_idr,document_json)
VALUES ('88888888-8888-4888-8888-888888888808',
        '88888888-8888-4888-8888-888888888805',
        '88888888-8888-4888-8888-888888888803',
        35000,'{"issuer":"synthetic-ci","purpose":"backup-restore"}'::jsonb);
UPDATE cash_drawer_shifts
SET closed_at=clock_timestamp(),
    closed_by='44444444-4444-4444-8444-444444444444',
    close_key='ci-restore-shift-close-unique',
    counted_cash_idr=44000,
    recorded_cash_idr=35000,
    expected_drawer_idr=45000,
    difference_idr=-1000,
    closing_reason='Synthetic counted drawer after physical receipt'
WHERE id='88888888-8888-4888-8888-888888888806';

INSERT INTO cash_variance_review_actions(shift_id,actor_staff_id,action_code,
                                         note,idempotency_key)
VALUES ('88888888-8888-4888-8888-888888888806',
        '44444444-4444-4444-8444-444444444444',
        'REQUEST_RECOUNT',
        'Synthetic cash difference requires an independent recount',
        'ci-restore-variance-review');
INSERT INTO audit_logs(id,actor_type,actor_id,operation,resource_type,resource_id,
                       correlation_id)
VALUES ('88888888-8888-4888-8888-888888888809',
        'STAFF','44444444-4444-4444-8444-444444444444',
        'CASH_VARIANCE_REVIEW','CASH_DRAWER_SHIFT',
        '88888888-8888-4888-8888-888888888806',
        'ci-restore-cash-variance');
