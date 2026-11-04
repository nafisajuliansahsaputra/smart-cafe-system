-- Initial paid-cash receipt model: corrections are deferred; never edit issued evidence.
CREATE TABLE receipts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_intent_id uuid NOT NULL UNIQUE REFERENCES payment_intents(id),
    order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
    receipt_number bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    total_idr bigint NOT NULL CHECK (total_idr > 0),
    currency char(3) NOT NULL DEFAULT 'IDR' CHECK (currency='IDR'),
    issued_at timestamptz NOT NULL DEFAULT now(),
    document_json jsonb NOT NULL
);

CREATE FUNCTION deny_receipt_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Issued receipts are immutable';
END;
$$;
CREATE TRIGGER receipt_no_update BEFORE UPDATE OR DELETE ON receipts
    FOR EACH ROW EXECUTE FUNCTION deny_receipt_mutation();

CREATE FUNCTION deny_order_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.visit_id,OLD.guest_id,OLD.subtotal_idr,OLD.discount_idr,OLD.tax_idr,
        OLD.service_fee_idr,OLD.total_idr,OLD.currency,OLD.pricing_policy_version)
       IS DISTINCT FROM
       (NEW.visit_id,NEW.guest_id,NEW.subtotal_idr,NEW.discount_idr,NEW.tax_idr,
        NEW.service_fee_idr,NEW.total_idr,NEW.currency,NEW.pricing_policy_version) THEN
       RAISE EXCEPTION 'Order financial snapshots are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER order_snapshot_no_update BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION deny_order_snapshot_mutation();
