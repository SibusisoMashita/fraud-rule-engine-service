-- Policy writers use one locked activation path; HTTP administration adds revision checks and actor audit.
ALTER TABLE fraud.policy_versions
    ADD COLUMN published_principal_id text REFERENCES fraud.api_principals (principal_id);
ALTER TABLE fraud.policy_activations
    ADD COLUMN activated_principal_id text REFERENCES fraud.api_principals (principal_id);

ALTER TABLE fraud.api_principals DROP CONSTRAINT api_principals_roles_check;
ALTER TABLE fraud.api_principals ADD CONSTRAINT api_principals_roles_check
    CHECK (cardinality(roles) BETWEEN 1 AND 4 AND array_ndims(roles) = 1
        AND array_position(roles, NULL) IS NULL
        AND roles <@ ARRAY['INGEST', 'READ', 'POLICY', 'POLICY_ADMIN']::text[]);

CREATE FUNCTION fraud._activate_policy(
    p_hash text, p_yaml text, p_only_if_empty boolean,
    p_expected_revision bigint, p_principal_id text)
RETURNS TABLE (revision bigint, policy_hash text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
    previous_hash text;
    previous_revision bigint;
    next_revision bigint;
BEGIN
    IF p_hash IS NULL OR p_yaml IS NULL OR p_only_if_empty IS NULL THEN
        RAISE EXCEPTION 'Policy activation requires all arguments' USING ERRCODE = '22004';
    END IF;
    IF p_expected_revision IS NOT NULL THEN
        IF p_expected_revision < 0 OR p_principal_id IS NULL THEN
            RAISE EXCEPTION 'Revision-guarded activation requires a valid revision and actor'
                USING ERRCODE = '22023';
        END IF;
        -- Hold the admitted actor's permission row until this activation commits.
        PERFORM 1 FROM fraud.api_principals p
            WHERE p.principal_id = p_principal_id AND p.enabled AND 'POLICY_ADMIN' = ANY(p.roles)
            FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Policy administration permission is required' USING ERRCODE = '42501';
        END IF;
    ELSIF p_principal_id IS NOT NULL THEN
        RAISE EXCEPTION 'An actor requires a guarded revision' USING ERRCODE = '22023';
    END IF;

    SELECT h.policy_hash, h.revision INTO previous_hash, previous_revision
        FROM fraud.policy_head h WHERE h.singleton FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active policy state is missing' USING ERRCODE = '55000';
    END IF;
    -- Compare revisions before considering a same-hash no-op, including A -> B -> A.
    IF p_expected_revision IS NOT NULL AND p_expected_revision <> previous_revision THEN
        RAISE EXCEPTION 'The active policy changed' USING ERRCODE = 'P0412';
    END IF;
    IF (p_only_if_empty AND previous_hash IS NOT NULL) OR previous_hash = p_hash THEN
        RETURN QUERY SELECT previous_revision, previous_hash;
        RETURN;
    END IF;

    INSERT INTO fraud.policy_versions (policy_hash, policy_yaml, published_by, published_principal_id)
        VALUES (p_hash, p_yaml, session_user, p_principal_id)
        ON CONFLICT ON CONSTRAINT policy_versions_pkey DO NOTHING;
    UPDATE fraud.policy_head h SET policy_hash = p_hash, revision = h.revision + 1
        WHERE h.singleton RETURNING h.revision INTO next_revision;
    INSERT INTO fraud.policy_activations (revision, policy_hash, activated_by, activated_principal_id)
        VALUES (next_revision, p_hash, session_user, p_principal_id);
    RETURN QUERY SELECT next_revision, p_hash;
END;
$$;
REVOKE ALL ON FUNCTION fraud._activate_policy(text, text, boolean, bigint, text)
    FROM PUBLIC, fraud_policy_admin, fraud_policy_api;

-- Preserve the existing CLI signature, seed behavior and session-user audit.
CREATE OR REPLACE FUNCTION fraud.activate_policy(p_hash text, p_yaml text, p_only_if_empty boolean)
RETURNS text LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT a.policy_hash FROM fraud._activate_policy(p_hash, p_yaml, p_only_if_empty, NULL, NULL) a;
$$;
REVOKE ALL ON FUNCTION fraud.activate_policy(text, text, boolean) FROM PUBLIC, fraud_policy_api;
GRANT EXECUTE ON FUNCTION fraud.activate_policy(text, text, boolean) TO fraud_policy_admin;

CREATE FUNCTION fraud.activate_policy_if_revision(
    p_hash text, p_yaml text, p_expected_revision bigint, p_principal_id text)
RETURNS TABLE (revision bigint, policy_hash text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF p_expected_revision IS NULL OR p_principal_id IS NULL THEN
        RAISE EXCEPTION 'Revision-guarded activation requires a revision and actor'
            USING ERRCODE = '22004';
    END IF;
    RETURN QUERY SELECT a.revision, a.policy_hash
        FROM fraud._activate_policy(p_hash, p_yaml, false, p_expected_revision, p_principal_id) a;
END;
$$;
REVOKE ALL ON FUNCTION fraud.activate_policy_if_revision(text, text, bigint, text)
    FROM PUBLIC, fraud_policy_admin;
GRANT USAGE ON SCHEMA fraud TO fraud_policy_api;
GRANT SELECT ON fraud.policy_versions, fraud.policy_head, fraud.policy_activations TO fraud_policy_api;
GRANT EXECUTE ON FUNCTION fraud.activate_policy_if_revision(text, text, bigint, text) TO fraud_policy_api;

UPDATE fraud.service_metadata SET version = 9 WHERE version = 8;
