-- Database-owned navigation fields preserve the original immutable response and support keyset indexes.
ALTER TABLE fraud.evaluations ADD COLUMN customer_id text, ADD COLUMN accepted_at timestamptz(6);
UPDATE fraud.evaluations e SET customer_id=t.customer_id,accepted_at=t.accepted_at
    FROM fraud.transactions t WHERE t.scope=e.scope AND t.event_id=e.event_id;
ALTER TABLE fraud.evaluations ALTER COLUMN customer_id SET NOT NULL, ALTER COLUMN accepted_at SET NOT NULL;
CREATE FUNCTION fraud.set_evaluation_query_fields() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
    SELECT customer_id,accepted_at INTO STRICT NEW.customer_id,NEW.accepted_at
        FROM fraud.transactions WHERE scope=NEW.scope AND event_id=NEW.event_id;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fraud.set_evaluation_query_fields() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fraud.set_evaluation_query_fields() TO fraud_runtime;
CREATE TRIGGER evaluation_query_fields BEFORE INSERT ON fraud.evaluations
    FOR EACH ROW EXECUTE FUNCTION fraud.set_evaluation_query_fields();
CREATE INDEX evaluations_scope_accepted_id ON fraud.evaluations(scope,accepted_at,id);
CREATE INDEX evaluations_customer_accepted_id ON fraud.evaluations(scope,customer_id,accepted_at,id);
CREATE INDEX evaluations_decision_accepted_id ON fraud.evaluations(scope,decision,accepted_at,id);
UPDATE fraud.service_metadata SET version=8 WHERE version=7;
