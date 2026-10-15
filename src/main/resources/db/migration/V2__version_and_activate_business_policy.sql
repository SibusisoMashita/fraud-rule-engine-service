CREATE TABLE fraud.policy_versions (
    policy_hash text PRIMARY KEY CHECK (policy_hash ~ '^[a-f0-9]{64}$'),
    policy_yaml text NOT NULL CHECK (octet_length(policy_yaml) BETWEEN 1 AND 1048576),
    published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_by text NOT NULL
);

CREATE TABLE fraud.policy_head (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    policy_hash text REFERENCES fraud.policy_versions (policy_hash),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    CHECK ((policy_hash IS NULL) = (revision = 0))
);
INSERT INTO fraud.policy_head (singleton) VALUES (true);

CREATE TABLE fraud.policy_activations (
    revision bigint PRIMARY KEY CHECK (revision > 0),
    policy_hash text NOT NULL REFERENCES fraud.policy_versions (policy_hash),
    activated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    activated_by text NOT NULL
);

-- The configuration identity cannot mutate policy rows or skip the activation history.
CREATE FUNCTION fraud.activate_policy(p_hash text, p_yaml text, p_only_if_empty boolean)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    previous_hash text;
    next_revision bigint;
BEGIN
    IF p_hash IS NULL OR p_yaml IS NULL OR p_only_if_empty IS NULL THEN
        RAISE EXCEPTION 'Policy activation requires all arguments';
    END IF;
    SELECT policy_hash INTO previous_hash FROM fraud.policy_head WHERE singleton FOR UPDATE;
    IF (p_only_if_empty AND previous_hash IS NOT NULL) OR previous_hash = p_hash THEN
        RETURN previous_hash;
    END IF;
    INSERT INTO fraud.policy_versions (policy_hash, policy_yaml, published_by)
        VALUES (p_hash, p_yaml, session_user) ON CONFLICT (policy_hash) DO NOTHING;
    UPDATE fraud.policy_head SET policy_hash = p_hash, revision = revision + 1
        WHERE singleton RETURNING revision INTO next_revision;
    INSERT INTO fraud.policy_activations (revision, policy_hash, activated_by)
        VALUES (next_revision, p_hash, session_user);
    RETURN p_hash;
END;
$$;
REVOKE ALL ON FUNCTION fraud.activate_policy(text, text, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION fraud.activate_policy(text, text, boolean) TO fraud_policy_admin;
GRANT USAGE ON SCHEMA fraud TO fraud_policy_admin;
GRANT SELECT ON fraud.policy_versions, fraud.policy_head, fraud.policy_activations
    TO fraud_runtime, fraud_policy_admin;
UPDATE fraud.service_metadata SET version = 2 WHERE version = 1;
