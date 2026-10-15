# Operations

This runbook covers the local runtime and policy configuration. It is not a deployment approval for real financial data. Initial setup and test commands are in the [README](../README.md).

## Lifecycle

```sh
docker compose ps -a
docker compose logs --tail=100 app migrate policy-seed db-bootstrap
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

Every policy selection reads the committed database reference. Parsed immutable versions are cached by hash, with no timer or stale-policy fallback. A selection observes one entire version even if activation overlaps it. Later ingestion will select once inside its processing transaction after the customer lock and record that hash. Ingestion itself is not implemented yet.

Initial Compose startup seeds the example only when no active version exists. Recreating the application or rerunning the seed job preserves business changes. Direct database calls bypass the Java validator: an invalid document or incompatible hash closes readiness until a valid compatible policy is activated. Database loss also closes readiness even with a cached version. No HTTP policy-management endpoint exists at this stage.

## Infrastructure: validated startup configuration

[Service limits](../config/service.example.yaml) load before runtime pools/listeners are constructed. They supply Hikari pool/acquisition/validation and SQL timeouts, Redis timeouts, HTTP worker/socket backlog bounds and connection timeout and shutdown grace. The socket backlog must be positive because Tomcat ignores a zero backlog setting. These validated settings take precedence over conflicting framework defaults. Transport validation, application admission queues, overall request deadlines, pagination and shared quotas remain later milestones; their settings are typed and checked but their enforcement is not yet implemented.

For an infrastructure change, update the service limits and recreate the application. If changing container stop grace, set `CONTAINER_STOP_GRACE_SECONDS` to the same value as `operations.containerStopGraceSeconds`; startup rejects a mismatch. Compose resource limits and edge/client settings remain deployment responsibilities. Database URLs and credentials are separate from the policy hash.

Configuration accepts one bounded UTF-8 YAML mapping: at most 1 MiB and 32 levels. Unknown/missing/duplicate fields, tags, anchors, aliases, merges, implicit YAML 1.1 booleans/octal values and scalar coercions are rejected. Monetary settings are quoted plain decimal strings. Configuration locations use `classpath:` or `file:`; remote fetching and environment interpolation inside policy documents are unsupported.

## Secrets and local identities

`init-secrets.sh` generates 256-bit local passwords without printing them and preserves existing files. The host directory is owner-only (0700); files inside are read-only (0444) for individual non-root container mounts. Git and the Docker build context exclude them. The runtime, migration and configuration identities have separate credentials. The local `db-bootstrap` job receives the database administrator credential only to create/grant the configuration role; the app never receives it. Redis currently allows connection commands only.

Initial database setup runs on an empty volume. The idempotent local role-bootstrap job also supports existing volumes; migrations then install the policy tables/function. Editing secret files alone does not rotate existing database passwords. Coordinate database role changes and mounted credentials before recreating clients. Secret-manager integration and rotation automation remain future deployment work.

## Probes and diagnosis

| Probe | Success | Failure | Meaning |
| --- | --- | --- | --- |
| `GET /health/live` | 200 `{"status":"UP"}` | Unreachable during termination | Process can serve HTTP; no shared dependency checks |
| `GET /health/ready` | 200 `{"status":"UP"}` | 503 `{"status":"DOWN"}` | Schema marker v2, a valid compatible active database policy and authenticated Redis ping succeed |

Probes expose no dependency names or diagnostics. Readiness verifies policy compatibility but does not authorize business traffic. All other routes are denied, including default login and diagnostic routes. Health routes belong on a restricted operational network when deployed externally.

If readiness fails, inspect `docker compose ps -a`, database/Redis health and application logs. PostgreSQL acquisition is bounded to 500 ms, statements to 1.5 seconds, and Redis connections/commands to 250 ms. These are individual dependency limits, not an overall fraud-request deadline. JDBC network reads are bounded to two seconds. Migration statements have a separate 30-second limit and a 35-second network-read budget. Public readiness recovers after dependencies return; liveness stays independent.

If migration exits nonzero, Compose blocks application startup. Inspect the migration log, resolve the underlying SQL/credential/schema problem, then rerun `docker compose up -d --no-build`. Never edit an applied migration or erase its history to force startup. Keep the existing data volume; use a reviewed forward migration for schema fixes. Backups, restore drills and rolling-release compatibility are later acceptance work.
