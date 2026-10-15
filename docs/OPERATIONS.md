# Operations

This runbook covers the local runtime and policy configuration. It is not a deployment approval for real financial data. Initial setup and test commands are in the [README](../README.md).

## Lifecycle

```sh
docker compose ps -a
docker compose logs --tail=100 app migrate policy-seed identity-seed db-bootstrap
docker compose stop app
docker compose up -d --no-build
docker compose down
```

`stop app` sends SIGTERM. Spring drains requests for up to 30 seconds; Compose allows 45 seconds before termination. `down` preserves the PostgreSQL volume. Redis is disposable and currently carries no business state. Container CPU, memory and process limits are defined in [Compose](../compose.yaml).

To discard this local database deliberately, `docker compose down --volumes` removes the project's data volume. It is not a normal restart or migration command.

## Business policy: live database configuration

The active business policy is selected through `fraud.policy_head`. `policy_versions` stores immutable source documents keyed by canonical hash; `policy_activations` records each activation revision, timestamp and database login. Runtime reads these tables but cannot change them. The dedicated `fraud_policy_admin` identity can activate only through the fixed-search-path database function; it cannot update/delete published versions or bypass activation history.

The current reference and recent activations can be inspected locally with:

```sh
docker compose exec -T db psql -U postgres -d fraud -c 'SELECT revision, policy_hash FROM fraud.policy_head'
docker compose exec -T db psql -U postgres -d fraud -c 'SELECT revision, policy_hash, activated_at, activated_by FROM fraud.policy_activations ORDER BY revision DESC LIMIT 10'
```

The [activation command](../scripts/activate-policy.sh) validates the exact input before invoking that function. An invalid file exits unsuccessfully without changing active policy. Activations serialize on the singleton head row; version insertion, pointer update and audit insertion commit together. Repeating the current hash is a no-op. To roll back a business change, activate its previous compatible policy document; this creates an auditable activation, not a history rewrite. The command uses the deployed image's rule definitions; algorithm changes require a compatible application release.

Every policy selection reads the committed database reference. Parsed immutable versions are cached by hash, with no timer or stale-policy fallback. A selection observes one entire version even if activation overlaps it. Ingestion selects once inside its processing transaction after the customer lock and records that hash. Existing identity is resolved before current currency/age eligibility; replays retain their original decision.

Initial Compose startup seeds the example only when no active version exists. Recreating the application or rerunning the seed job preserves business changes. Direct database calls bypass the Java validator: an invalid document or incompatible hash closes readiness until a valid compatible policy is activated. Database loss also closes readiness even with a cached version. No HTTP policy-management endpoint exists at this stage.

## Infrastructure: validated startup configuration

[Service limits](../config/service.example.yaml) load before runtime pools/listeners are constructed. They supply Hikari pool/acquisition/validation and SQL timeouts, Redis timeouts, HTTP workers/connections/socket backlog, header/discarded-body bounds, connection/keep-alive idle timeout and shutdown grace. The socket backlog must be positive because Tomcat ignores a zero backlog setting. These validated settings take precedence over conflicting framework defaults. Protected ingress enforces the body/parser bounds, nonblocking admission and shared quotas. Pagination, absolute upload deadlines and complete transactional cancellation/resource-release drills remain later work. SQL work has decreasing statement/lock budgets, a separate commit reserve and a derived Spring transaction timeout. Application admission has no waiting queue; the configured backlog belongs to the connector.

For an infrastructure change, update the service limits and recreate the application. If changing container stop grace, set `CONTAINER_STOP_GRACE_SECONDS` to the same value as `operations.containerStopGraceSeconds`; startup rejects a mismatch. Compose resource limits and edge/client settings remain deployment responsibilities. Database URLs and credentials are separate from the policy hash.

Configuration accepts one bounded UTF-8 YAML mapping: at most 1 MiB and 32 levels. Unknown/missing/duplicate fields, tags, anchors, aliases, merges, implicit YAML 1.1 booleans/octal values and scalar coercions are rejected. Monetary settings are quoted plain decimal strings. Configuration locations use `classpath:` or `file:`; remote fetching and environment interpolation inside policy documents are unsupported.

## Secrets and local identities

`init-secrets.sh` generates 256-bit local passwords without printing them and preserves existing files. The host directory is owner-only (0700); files inside are read-only (0444) for individual non-root container mounts. Git and the Docker build context exclude them. The runtime, migration, business-policy and security-configuration identities have separate credentials. The local `db-bootstrap` job receives the database administrator credential only to create/grant the configuration roles; the app never receives it. The local API key is generated separately and is never mounted into the app. Redis permits connection commands plus the bounded script/hash/expiry commands for `fraud:quota:*`; arbitrary data keys, CONFIG and general writes are denied.

Initial database setup runs on an empty volume. The idempotent local role-bootstrap job also supports existing volumes; migrations then install the policy tables/function. Editing secret files alone does not rotate existing database passwords. Coordinate database role changes and mounted credentials before recreating clients. Secret-manager integration and rotation automation remain future deployment work.

## Identities, permissions and rotation

`fraud.api_principals` owns immutable principal/scope identity, enabled state, roles, trusted location sources and an automatic permission revision. `fraud.api_credentials` owns key ID, SHA-256 secret hash, finite validity interval and revocation. The wire key is a 1–64 character ASCII key ID (`A–Z`, `a–z`, digits, `_`, `-`), a dot and a 64-character lowercase hexadecimal secret. Secrets are generated from 32 random bytes. No key, role or scope is accepted through a URL or tenant header.

Authentication reads current database state on every request. Disabling a principal, revoking a key or changing its roles/source permissions applies to the next admission without app restart. An already admitted request retains its captured permissions. Identity changes and audit entries commit or roll back together. Audit records contain identifiers, database actor and timestamp; they contain no key hash or plaintext secret. Runtime cannot change identities or audit. Security administration cannot rewrite scope, credential ownership/hash, or existing audit entries.

For example, this local administrative transaction revokes the demo key without mounting security credentials into the app:

```sh
docker compose run --rm -T --no-deps --entrypoint /bin/sh identity-seed -ec \
  'export PGPASSWORD="$(cat /run/secrets/db-security-password)"; exec psql -h db -U fraud_security_admin -d fraud -v ON_ERROR_STOP=1' <<'SQL'
BEGIN;
UPDATE fraud.api_credentials SET revoked = true WHERE key_id = 'local';
COMMIT;
SQL
```

The same identity manages `enabled`, `roles`, `location_sources` and validity intervals through validated database columns. Rotation inserts a newly generated key ID/secret hash with an explicit validity interval for the existing principal, verifies the new credential, then revokes the old key. Both keys share the principal's quota. Transfer the plaintext secret through the deployment's secret-delivery mechanism; PostgreSQL receives only its SHA-256 hash. A different scope requires a new principal. External identity-provider/secret-manager integration is outside the local assessment runtime.

The local seed uses `local-demo` with all three roles and both verified physical sources, plus `secrets/local-api-key`. `LOCAL_KEY_VALIDITY_DAYS` configures initial expiry (default 30 days). Rerunning it preserves existing principals/credentials, including disabled, expired or revoked records; editing/regenerating a secret file is not credential rotation.

## Admission and quota behaviour

| Boundary | Current behaviour |
| --- | --- |
| Credentials | Missing, malformed, duplicate, expired, revoked or unknown key: 401 and `WWW-Authenticate: ApiKey` |
| Authorization | Wrong route role or unpermitted physical source: 403; client scope/proxy headers grant no permissions |
| Input | Unsupported or duplicate media/encoding headers: 415; excess body: 413; malformed/duplicate/deep JSON or invalid shape: 400; invalid static domain facts: 422 |
| Quota | Stable principal/route bucket shared by replicas and rotated keys; exhaustion: 429 with `Retry-After` |
| Dependencies/capacity | Admission dependency, quota-script, Redis memory or permit failure: 503 with a retry hint; cached/open access is not used |
| Transactions | New committed POST: 201; identical replay: 200; changed content or instrument owner: 409; own-scope GET: 200 or masked 404 |
| Remaining queries | Evaluation-list and rule-inspection handlers remain 503 `FEATURE_UNAVAILABLE` |

Errors use `application/problem+json`, a server-generated correlation ID and `Cache-Control: no-store`. Admission and confirmed processing rollback report `NOT_ACCEPTED`; commit/completion uncertainty reports `UNKNOWN`. They omit request/credential values and dependency diagnostics. An uncertain outcome requires scoped GET or replay of the identical ID/payload; a temporary 404 does not prove that another in-flight attempt cannot commit.

Quota settings remain startup infrastructure configuration. Redis uses its own clock, clamps backward movement and caps refill at capacity. Idle state has a bounded expiry. Redis restart, lost state/expiry or a forward clock jump can restore allowance; this is an abuse quota, not a durable financial limit. Redis noeviction/script/ACL/timeout errors reject protected traffic. Readiness PING checks dependency connectivity; protected smoke checks additionally prove the script permissions work.

Tomcat worker/connection/backlog/header bounds and idle timeouts, bounded JSON reads and a nonwaiting admission permit bound resource use. Deadline checks surround admission and follow decoding. Absolute slow-upload deadlines, pre-authentication edge quotas and complete transactional cancellation under network faults remain deployment/later acceptance work. Query filter/pagination semantics arrive with the query milestone.

## Transactional storage and compatible upgrades

Runtime has SELECT/INSERT on immutable transactions, evaluations, rule results and instrument owners. Only customer-state acceptance time/sequence can be updated. Deferred event/evaluation and result-completeness constraints prevent partial committed records. Customer locks serialize processing within `(scope,customerId)`; separate customers can proceed independently. Producer identity, source-permission revision and admitted source snapshot are stored without credentials. Policy activation never rewrites evaluations.

Only high amount has an execution implementation in this build. The sample disables the remaining four rules. Activation rejects enabling an unavailable rule; bypassing the validator through raw SQL closes readiness and new-event selection until a compatible policy is restored. Existing results remain immutable.

For an existing Stage 3 volume, build the new image, prepare a working copy of the active policy that preserves its business thresholds/currencies while disabling unavailable implementations, activate that copy, then recreate the stack. For the unchanged local sample, the sequence is:

```sh
docker compose build app
./scripts/activate-policy.sh config/policy.example.yaml
docker compose up -d --no-build --wait --wait-timeout 120
```

Use the active-policy working copy instead of the sample when settings have been customized. This is an explicit configuration activation, not a migration that silently changes business rules. Flyway V4 upgrades storage and preserves existing policy/identity data. Fresh startup seeds the compatible sample automatically.

A POST success contains the original policy hash, evaluator artifact identity, accepted/evaluated times, preceding customer watermark and all configured results. Equality at the high-amount threshold is a nonmatch. Thresholds, currencies, windows and toggles are read from database policy, with no Java business defaults. Disabled results are explicit; an all-disabled policy remains invalid.

Rule failures and work-budget expiry before commit roll back all records. Completion failures can be ambiguous even when the server later proves rollback, so the API conservatively returns `UNKNOWN` where commit/cleanup cannot be confirmed. Transport loss after commit can hide a successful response. Retry the identical event, never a replacement ID. Original decisions and provenance remain retrievable after restart or subsequent policy changes. Full process-kill/network/backup recovery drills remain release acceptance work.

## Probes and diagnosis

| Probe | Success | Failure | Meaning |
| --- | --- | --- | --- |
| `GET /health/live` | 200 `{"status":"UP"}` | Unreachable during termination | Process can serve HTTP; no shared dependency checks |
| `GET /health/ready` | 200 `{"status":"UP"}` | 503 `{"status":"DOWN"}` | Schema marker v4, a valid compatible active database policy and authenticated Redis ping succeed |

Probes expose no dependency names or diagnostics. Readiness verifies policy compatibility but does not authorize business traffic. Transaction POST and event GET enforce admission and execute the implemented flow. List/policy-inspection handlers remain `503 FEATURE_UNAVAILABLE`. Default login, diagnostic and unknown routes remain denied. Health routes belong on a restricted operational network when deployed externally.

If readiness fails, inspect `docker compose ps -a`, database/Redis health and application logs. PostgreSQL acquisition is bounded to 500 ms, statements to 1.5 seconds, and Redis connections/commands to 250 ms. These are individual dependency limits, not an overall fraud-request deadline. JDBC network reads are bounded to two seconds. Migration statements have a separate 30-second limit and a 35-second network-read budget. Public readiness recovers after dependencies return; liveness stays independent.

If migration exits nonzero, Compose blocks application startup. Inspect the migration log, resolve the underlying SQL/credential/schema problem, then rerun `docker compose up -d --no-build`. Never edit an applied migration or erase its history to force startup. Keep the existing data volume; use a reviewed forward migration for schema fixes. Backups, restore drills and rolling-release compatibility are later acceptance work.
