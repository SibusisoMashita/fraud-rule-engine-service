CREATE TABLE fraud.api_principals (
    principal_id text PRIMARY KEY CHECK (principal_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    scope text NOT NULL CHECK (scope ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    enabled boolean NOT NULL,
    roles text[] NOT NULL CHECK (cardinality(roles) BETWEEN 1 AND 3 AND array_ndims(roles) = 1 AND array_position(roles, NULL) IS NULL
        AND roles <@ ARRAY['INGEST', 'READ', 'POLICY']::text[]),
    location_sources text[] NOT NULL CHECK (cardinality(location_sources) <= 2 AND (cardinality(location_sources) = 0 OR array_ndims(location_sources) = 1) AND array_position(location_sources, NULL) IS NULL
        AND location_sources <@ ARRAY['VERIFIED_ATM_TERMINAL', 'VERIFIED_CARD_PRESENT_TERMINAL']::text[]),
    permission_version bigint NOT NULL DEFAULT 1 CHECK (permission_version > 0)
);
CREATE TABLE fraud.api_credentials (
    key_id text PRIMARY KEY CHECK (key_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    principal_id text NOT NULL REFERENCES fraud.api_principals(principal_id),
    secret_hash text NOT NULL CHECK (secret_hash ~ '^[a-f0-9]{64}$'),
    valid_from timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false,
    CHECK (isfinite(valid_from) AND isfinite(expires_at) AND expires_at > valid_from)
);
CREATE TABLE fraud.identity_changes (
    revision bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity text NOT NULL,
    entity_id text NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    changed_by text NOT NULL
);
CREATE FUNCTION fraud.guard_identity_change() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF TG_TABLE_NAME = 'api_principals' THEN
        IF TG_OP = 'UPDATE' THEN
            IF NEW.principal_id <> OLD.principal_id OR NEW.scope <> OLD.scope THEN
                RAISE EXCEPTION 'Principal identity and scope are immutable';
            END IF;
            NEW.permission_version := OLD.permission_version + 1;
        ELSE
            NEW.permission_version := 1;
        END IF;
    ELSE
        IF TG_OP = 'UPDATE' AND (NEW.key_id <> OLD.key_id OR NEW.principal_id <> OLD.principal_id OR NEW.secret_hash <> OLD.secret_hash) THEN
            RAISE EXCEPTION 'Rotate credentials by creating a new key ID';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION fraud.guard_identity_change() FROM PUBLIC;
CREATE FUNCTION fraud.audit_identity_change() RETURNS trigger LANGUAGE plpgsql
SECURITY DEFINER SET search_path = pg_catalog AS $$
BEGIN
    IF TG_TABLE_NAME = 'api_principals' THEN
        INSERT INTO fraud.identity_changes(entity, entity_id, changed_by) VALUES ('principal', NEW.principal_id, session_user);
    ELSE
        INSERT INTO fraud.identity_changes(entity, entity_id, changed_by) VALUES ('credential', NEW.key_id, session_user);
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION fraud.audit_identity_change() FROM PUBLIC;
CREATE TRIGGER principal_guard BEFORE INSERT OR UPDATE ON fraud.api_principals
    FOR EACH ROW EXECUTE FUNCTION fraud.guard_identity_change();
CREATE TRIGGER credential_guard BEFORE INSERT OR UPDATE ON fraud.api_credentials
    FOR EACH ROW EXECUTE FUNCTION fraud.guard_identity_change();
CREATE TRIGGER principal_audit AFTER INSERT OR UPDATE ON fraud.api_principals
    FOR EACH ROW EXECUTE FUNCTION fraud.audit_identity_change();
CREATE TRIGGER credential_audit AFTER INSERT OR UPDATE ON fraud.api_credentials
    FOR EACH ROW EXECUTE FUNCTION fraud.audit_identity_change();
GRANT USAGE ON SCHEMA fraud TO fraud_security_admin;
GRANT SELECT, INSERT, UPDATE ON fraud.api_principals, fraud.api_credentials TO fraud_security_admin;
GRANT SELECT ON fraud.identity_changes TO fraud_security_admin;
GRANT SELECT ON fraud.api_principals, fraud.api_credentials TO fraud_runtime;
UPDATE fraud.service_metadata SET version = 3 WHERE version = 2;
