ALTER TABLE fraud.api_principals ADD CONSTRAINT principal_scope_identity UNIQUE (principal_id, scope);
CREATE TABLE fraud.customer_processing_state (
    scope text NOT NULL CHECK (scope ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    customer_id text NOT NULL CHECK (customer_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    last_accepted_at timestamptz(6),
    customer_sequence bigint NOT NULL DEFAULT 0 CHECK (customer_sequence >= 0),
    PRIMARY KEY (scope, customer_id),
    CHECK ((customer_sequence = 0) = (last_accepted_at IS NULL)),
    CHECK (last_accepted_at IS NULL OR (isfinite(last_accepted_at)
        AND last_accepted_at >= '0001-01-01 00:00:00+00'::timestamptz AND last_accepted_at < '10000-01-01 00:00:00+00'::timestamptz))
);
CREATE TABLE fraud.instrument_owners (
    scope text NOT NULL,
    instrument_id text NOT NULL CHECK (instrument_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    customer_id text NOT NULL,
    PRIMARY KEY (scope, instrument_id),
    UNIQUE (scope, instrument_id, customer_id),
    FOREIGN KEY (scope, customer_id) REFERENCES fraud.customer_processing_state(scope, customer_id)
);
CREATE TABLE fraud.transactions (
    scope text NOT NULL,
    event_id text NOT NULL CHECK (event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    customer_id text NOT NULL,
    instrument_id text NOT NULL,
    request_hash text NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    canonicalization_version integer NOT NULL CHECK (canonicalization_version = 1),
    transaction_type text NOT NULL CHECK (transaction_type IN ('CARD_PURCHASE', 'ATM_WITHDRAWAL')),
    status text NOT NULL CHECK (status = 'BOOKED'),
    channel text NOT NULL CHECK (channel IN ('CARD_PRESENT', 'ECOMMERCE', 'ATM')),
    amount numeric(20,4) NOT NULL CHECK (amount > 0 AND amount < 'Infinity'::numeric),
    currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    category text NOT NULL CHECK (length(category) BETWEEN 1 AND 64),
    occurred_at timestamptz(6) NOT NULL CHECK (isfinite(occurred_at)
        AND occurred_at >= '0001-01-01 00:00:00+00'::timestamptz AND occurred_at < '10000-01-01 00:00:00+00'::timestamptz),
    accepted_at timestamptz(6) NOT NULL CHECK (isfinite(accepted_at)
        AND accepted_at >= '0001-01-01 00:00:00+00'::timestamptz AND accepted_at < '10000-01-01 00:00:00+00'::timestamptz),
    customer_sequence bigint NOT NULL CHECK (customer_sequence > 0),
    producer_id text NOT NULL,
    source_permission_version bigint NOT NULL CHECK (source_permission_version > 0),
    source_permissions jsonb NOT NULL CHECK (jsonb_typeof(source_permissions) = 'array' AND jsonb_array_length(source_permissions) <= 2),
    latitude numeric,
    longitude numeric,
    accuracy_km numeric,
    location_source text CHECK (location_source IN ('VERIFIED_ATM_TERMINAL', 'VERIFIED_CARD_PRESENT_TERMINAL')),
    city text CHECK (length(city) <= 128),
    input_json jsonb NOT NULL CHECK (jsonb_typeof(input_json) = 'object' AND octet_length(input_json::text) <= 16384),
    PRIMARY KEY (scope, event_id),
    UNIQUE (scope, customer_id, customer_sequence),
    FOREIGN KEY (scope, instrument_id, customer_id) REFERENCES fraud.instrument_owners(scope, instrument_id, customer_id),
    FOREIGN KEY (producer_id, scope) REFERENCES fraud.api_principals(principal_id, scope),
    CHECK ((transaction_type = 'ATM_WITHDRAWAL') = (channel = 'ATM')),
    CHECK ((location_source IS NULL AND latitude IS NULL AND longitude IS NULL AND accuracy_km IS NULL AND city IS NULL)
        OR (location_source IS NOT NULL AND latitude IS NOT NULL AND longitude IS NOT NULL AND accuracy_km IS NOT NULL
            AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
            AND accuracy_km >= 0 AND accuracy_km < 'Infinity'::numeric
            AND channel <> 'ECOMMERCE'
            AND (channel = 'ATM') = (location_source = 'VERIFIED_ATM_TERMINAL')
            AND source_permissions ? location_source))
);
CREATE TABLE fraud.evaluations (
    scope text NOT NULL,
    event_id text NOT NULL,
    id uuid NOT NULL UNIQUE,
    policy_hash text NOT NULL REFERENCES fraud.policy_versions(policy_hash),
    build_id text NOT NULL CHECK (length(build_id) BETWEEN 1 AND 128),
    context_high_watermark bigint NOT NULL CHECK (context_high_watermark >= 0),
    decision text NOT NULL CHECK (decision IN ('CLEAR', 'FLAGGED', 'INDETERMINATE')),
    coverage text NOT NULL CHECK (coverage IN ('COMPLETE', 'PARTIAL')),
    evaluated_at timestamptz(6) NOT NULL CHECK (isfinite(evaluated_at)
        AND evaluated_at >= '0001-01-01 00:00:00+00'::timestamptz AND evaluated_at < '10000-01-01 00:00:00+00'::timestamptz),
    expected_rule_count integer NOT NULL CHECK (expected_rule_count BETWEEN 1 AND 64),
    PRIMARY KEY (scope, event_id),
    FOREIGN KEY (scope, event_id) REFERENCES fraud.transactions(scope, event_id)
);
-- Both directions enforce the event/evaluation pair at commit, without an intermediate accepted state.
ALTER TABLE fraud.transactions ADD CONSTRAINT transaction_has_evaluation
    FOREIGN KEY (scope, event_id) REFERENCES fraud.evaluations(scope, event_id) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION fraud.valid_rule_evidence(document jsonb) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE STRICT SET search_path = pg_catalog AS $$
DECLARE fact record; total integer := 0; version numeric;
BEGIN
    IF jsonb_typeof(document) <> 'object' OR NOT (document ? 'evidenceSchemaVersion')
        OR jsonb_typeof(document->'evidenceSchemaVersion') <> 'number' THEN RETURN false; END IF;
    version := (document->>'evidenceSchemaVersion')::numeric;
    IF version <= 0 OR trunc(version) <> version THEN RETURN false; END IF;
    FOR fact IN SELECT key, value FROM jsonb_each(document) LOOP
        total := total + 1;
        IF total > 16 OR fact.key !~ '^[a-zA-Z][a-zA-Z0-9]{0,63}$' THEN RETURN false; END IF;
        IF jsonb_typeof(fact.value) = 'string' THEN
            IF length(fact.value #>> '{}') > 256 THEN RETURN false; END IF;
        ELSIF jsonb_typeof(fact.value) = 'number' THEN
            IF length(fact.value::text) > 32 THEN RETURN false; END IF;
        ELSIF jsonb_typeof(fact.value) <> 'boolean' THEN RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION fraud.valid_rule_evidence(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fraud.valid_rule_evidence(jsonb) TO fraud_runtime;
CREATE TABLE fraud.rule_results (
    scope text NOT NULL,
    event_id text NOT NULL,
    rule_id text NOT NULL CHECK (rule_id ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    algorithm_version text NOT NULL CHECK (length(algorithm_version) BETWEEN 1 AND 32),
    status text NOT NULL CHECK (status IN ('MATCH','NO_MATCH','NOT_APPLICABLE','INSUFFICIENT_DATA','DISABLED')),
    reason_code text NOT NULL CHECK (length(reason_code) BETWEEN 1 AND 64),
    evidence jsonb NOT NULL CHECK (octet_length(evidence::text) <= 32768 AND fraud.valid_rule_evidence(evidence)),
    PRIMARY KEY (scope, event_id, rule_id),
    FOREIGN KEY (scope, event_id) REFERENCES fraud.evaluations(scope, event_id)
);
CREATE FUNCTION fraud.require_complete_results() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE expected integer; actual bigint;
BEGIN
    SELECT expected_rule_count INTO expected FROM fraud.evaluations WHERE scope = NEW.scope AND event_id = NEW.event_id;
    SELECT count(*) INTO actual FROM fraud.rule_results WHERE scope = NEW.scope AND event_id = NEW.event_id;
    IF expected IS NULL OR expected <> actual THEN
        RAISE EXCEPTION 'Evaluation result set is incomplete' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION fraud.require_complete_results() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fraud.require_complete_results() TO fraud_runtime;
CREATE CONSTRAINT TRIGGER evaluation_results_complete AFTER INSERT ON fraud.evaluations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fraud.require_complete_results();
CREATE CONSTRAINT TRIGGER result_set_complete AFTER INSERT ON fraud.rule_results
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fraud.require_complete_results();
GRANT SELECT, INSERT ON fraud.transactions, fraud.evaluations, fraud.rule_results, fraud.instrument_owners TO fraud_runtime;
GRANT SELECT, INSERT ON fraud.customer_processing_state TO fraud_runtime;
GRANT UPDATE (last_accepted_at, customer_sequence) ON fraud.customer_processing_state TO fraud_runtime;
UPDATE fraud.service_metadata SET version = 4 WHERE version = 3;
