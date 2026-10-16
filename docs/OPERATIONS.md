# Operations

Use this guide to run the local stack, change policy, manage credentials and diagnose failures. Start with the [README](../README.md#quickstart) for a fresh installation. Requirements for real financial data are listed in [Testing](TESTING.md#before-a-real-data-deployment).

## First start and troubleshooting

The README starts a fresh database, runs migrations and seeds the sample policy and local API identity. `init-secrets.sh` creates credentials in the ignored `secrets/` directory and preserves existing files. Later starts also preserve policy changes, evaluations and identity changes, including revocation.

If port 8080 is occupied, choose an unused port and use it for subsequent requests:

```sh
APP_PORT=18080 docker compose up -d --no-build --wait --wait-timeout 120
curl --fail http://localhost:18080/health/ready
```

Use `docker compose ps -a` and `docker compose logs --tail=100 app migrate policy-seed identity-seed db-bootstrap` to find a failed startup job. [Probes and diagnosis](#probes-and-diagnosis) explains readiness failures. Initial image/dependency downloads need internet access.

If you already have a database created by an earlier Debian PostgreSQL image, follow [Database image migration](#database-image-migration) before starting this image. The startup guard rejects that older data directory; keep the volume and migrate it rather than deleting it to get past the error.

## Lifecycle

```sh
docker compose ps -a
docker compose logs --tail=100 app migrate policy-seed identity-seed db-bootstrap
docker compose stop app
docker compose up -d --no-build
docker compose down
```

`stop app` sends SIGTERM. Spring drains requests for up to 30 seconds; Compose allows 45 seconds before termination. `down` preserves the PostgreSQL volume. Redis is disposable and currently carries no business state. Container CPU, memory and process limits are defined in [Compose](../compose.yaml).

To deliberately delete the local database, use `docker compose down --volumes`. This removes the project's data volume; ordinary restarts use `down` without `--volumes`.

## Business policy: live database configuration

To change business settings, edit a working copy and activate it against the running stack:

```sh
cp config/policy.example.yaml secrets/my-policy.yaml
# Edit secrets/my-policy.yaml, then run:
./scripts/activate-policy.sh secrets/my-policy.yaml
```

The working copy stays outside Git. Editing a file alone does not change the active policy. New evaluations use the updated version without restarting; saved evaluations keep their original results.

The service reads the active policy through `fraud.policy_head`. `policy_versions` stores immutable source documents keyed by canonical hash; `policy_activations` records each activation revision, timestamp and database login. Runtime reads these tables but cannot change them. The dedicated `fraud_policy_admin` identity can activate only through the fixed-search-path database function; it cannot update/delete published versions or bypass activation history.

The current reference and recent activations can be inspected locally with:

```sh
docker compose exec -T db psql -U postgres -d fraud -c 'SELECT revision, policy_hash FROM fraud.policy_head'
docker compose exec -T db psql -U postgres -d fraud -c 'SELECT revision, policy_hash, activated_at, activated_by FROM fraud.policy_activations ORDER BY revision DESC LIMIT 10'
```

The [activation command](../scripts/activate-policy.sh) validates the exact input before invoking that function. An invalid file exits unsuccessfully without changing active policy. Activations serialize on the singleton head row; version insertion, pointer update and audit insertion commit together. Repeating the current hash is a no-op. To roll back a business change, activate its previous compatible policy document; this creates an auditable activation, not a history rewrite. The command uses the deployed image's rule definitions; algorithm changes require a compatible application release.

Each evaluation reads the committed policy reference once, after taking the customer lock, and records its hash. That whole version remains in use even if someone activates a change during processing. Parsed versions are cached by hash; the service still reads the current reference every time and never falls back to a stale policy. Identical retries return the saved evaluation before checking current new-event currency/age eligibility.

Initial Compose startup seeds the example only when no active version exists. Recreating the application or rerunning the seed job preserves business changes. Direct database calls bypass the Java validator: an invalid document or incompatible hash closes readiness until a valid compatible policy is activated. Database loss also closes readiness even with a cached version. Policy changes use the operator CLI; there is no HTTP write endpoint.

## Infrastructure: validated startup configuration

[Service limits](../config/service.example.yaml) load before runtime pools/listeners are constructed. They supply Hikari pool/acquisition/validation and SQL timeouts, Redis timeouts, HTTP workers/connections/socket backlog, header/discarded-body bounds, connection/keep-alive idle timeout and shutdown grace. The socket backlog must be positive because Tomcat ignores a zero backlog setting. These validated settings take precedence over conflicting framework defaults. Protected ingress enforces the body/parser bounds, nonblocking admission and shared quotas. The application alone does not guarantee an absolute upload deadline or complete cancellation/resource release under every network fault. The optional [HAProxy setup](SCALING.md#readiness-aware-routing) adds edge body-wait and request deadlines; the tested cases are listed in [Testing](TESTING.md#load-balancing-and-local-autoscaling). SQL work has decreasing statement/lock budgets, a separate commit reserve and a derived Spring transaction timeout. Application admission has no waiting queue; the configured backlog belongs to the connector.

For an infrastructure change, update the service limits and recreate the application. If changing container stop grace, set `CONTAINER_STOP_GRACE_SECONDS` to the same value as `operations.containerStopGraceSeconds`; startup rejects a mismatch. Compose resource limits and edge/client settings remain deployment responsibilities. Database URLs and credentials are separate from the policy hash.

Configuration accepts one bounded UTF-8 YAML mapping: at most 1 MiB and 32 levels. Unknown/missing/duplicate fields, tags, anchors, aliases, merges, implicit YAML 1.1 booleans/octal values and scalar coercions are rejected. Monetary settings are quoted plain decimal strings. Configuration locations use `classpath:` or `file:`; remote fetching and environment interpolation inside policy documents are unsupported.

## Secrets and local identities

`init-secrets.sh` generates 256-bit local passwords without printing them. Existing files are kept. The host directory is owner-only (0700); files inside are read-only (0444) for individual non-root container mounts. Git and the Docker build context exclude them. The runtime, migration, business-policy and security-configuration identities have separate credentials. The local `db-bootstrap` job receives the database administrator credential only to create/grant the configuration roles; the app never receives it.

The local API key is generated separately and is never mounted into the app. Redis permits connection commands plus the bounded script/hash/expiry commands for `fraud:quota:*`; arbitrary data keys, CONFIG and general writes are denied.

Initial database setup runs on an empty volume. The idempotent local role-bootstrap job also supports existing volumes; migrations then install the policy tables/function. Editing secret files alone does not rotate existing database passwords. Coordinate database role changes and mounted credentials before recreating clients. Secret-manager integration and rotation automation remain future deployment work.

## Identities, permissions and rotation

`fraud.api_principals` stores the principal and scope, enabled state, roles, trusted location sources and permission revision. Principal and scope identities cannot be changed. `fraud.api_credentials` stores each key ID, SHA-256 secret hash, validity interval and revocation state. The wire key is a 1–64 character ASCII key ID (`A–Z`, `a–z`, digits, `_`, `-`), a dot and a 64-character lowercase hexadecimal secret. Secrets are generated from 32 random bytes. No key, role or scope is accepted through a URL or tenant header.

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
| Queries | Evaluation pages require READ; live rule inspection requires POLICY; each uses its own shared route quota |

Application errors use `application/problem+json`, a server-generated correlation ID and `Cache-Control: no-store`. HAProxy can reject requests before they reach the app; those errors use the proxy's own response format. Admission and confirmed processing rollback report `NOT_ACCEPTED`; commit/completion uncertainty reports `UNKNOWN`. They omit request/credential values and dependency diagnostics. An uncertain outcome requires scoped GET or replay of the identical ID/payload; a temporary 404 does not prove that another in-flight attempt cannot commit.

Quota settings remain startup infrastructure configuration. Redis uses its own clock, clamps backward movement and caps refill at capacity. Idle state has a bounded expiry. Redis restart, lost state/expiry or a forward clock jump can restore allowance; this is an abuse quota, not a durable financial limit. Redis noeviction/script/ACL/timeout errors reject protected traffic. Readiness PING checks dependency connectivity; protected smoke checks additionally prove the script permissions work.

Tomcat worker/connection/backlog/header bounds and idle timeouts, bounded JSON reads and a nonwaiting admission permit bound resource use. Deadline checks surround admission and follow decoding. The single-instance setup does not enforce absolute upload deadlines or pre-authentication edge quotas. The optional HAProxy setup adds request/body-wait deadlines; complete cancellation under every network fault remains unverified. Evaluation queries enforce configured page/date bounds and strict filter validation.

## Transactional storage and compatible upgrades

Runtime has SELECT/INSERT on immutable transactions, evaluations, rule results and instrument owners. Only customer-state acceptance time/sequence can be updated. Deferred event/evaluation and result-completeness constraints prevent partial committed records. Customer locks serialize processing within `(scope,customerId)`; separate customers can proceed independently. Producer identity, source-permission revision and admitted source snapshot are stored without credentials. Policy activation never rewrites evaluations.

All five rules are implemented and enabled in the sample policy. Activation rejects enabling an unavailable rule; bypassing the validator through raw SQL closes readiness and new-event selection until a compatible policy is restored. Existing results remain immutable.

Existing installations retain their active policy while upgrading. Prepare a compatible [startup configuration](../config/service.example.yaml), including the travel candidate safety ceiling and documentation toggle. Drain ingestion before applying migrations; this build requires schema marker 8, so older images need a coordinated release. For an installation already using the guarded Alpine image family, use the commands below. Earlier Debian installations first require the [logical migration](#database-image-migration). For local Compose:

```sh
./scripts/init-secrets.sh
docker compose build app
docker compose stop app
docker compose up -d --no-build --wait --wait-timeout 120
```

Flyway applies only pending migrations and preserves evaluations, identities and policy history. V8 backfills evaluation navigation fields and builds scoped acceptance-time/customer/decision indexes. Backfill and index creation use normal PostgreSQL locking; drain ingestion and schedule the migration for a large existing dataset. Keep the data volume and applied migration history. Fresh startup seeds the compatible sample only when no active policy exists.

This upgrade requires no business-policy activation. Existing rule settings and enabled states remain authoritative. To change them, activate a validated working copy using the business-policy procedure above; recreating the app or editing the seed file does not activate business changes.

The live travel candidate cap must not exceed the startup infrastructure ceiling. Activation and runtime loading reject incompatible policies; increasing business limits above that ceiling first requires a deliberate infrastructure release. Speed, distance, allowance, comparison age, accuracy, source/channel filters and enabled state otherwise take effect without app restart. Every new evaluation uses one captured policy; old results remain immutable.

A successful POST includes the captured policy hash, application artifact identity, accepted/evaluated times, preceding customer watermark and all configured rule results. All thresholds, currencies, windows and toggles come from database policy. Disabled rules have explicit results; an all-disabled policy is invalid. [Rules](RULES.md) defines the predicates and event-history boundaries for each rule. Late events and later policy changes never rewrite a saved evaluation.

Rule failures and work-budget expiry before commit roll back all records. Completion failures can be ambiguous even when the server later proves rollback, so the API conservatively returns `UNKNOWN` where commit/cleanup cannot be confirmed. Transport loss after commit can hide a successful response. Retry the identical event, never a replacement ID. Original decisions and provenance remain retrievable after restart or subsequent policy changes. The operational harness below covers controlled process, acknowledgement-loss and logical-restore drills; production recovery qualification remains deployment-specific.

## Query access and cursor keys

Use the generated local key to list evaluations or inspect the current policy:

```sh
printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
  curl --config - --include 'http://localhost:8080/api/v1/evaluations?customerId=demo-customer&pageSize=2'
printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
  curl --config - --include http://localhost:8080/api/v1/rules
```

`GET /api/v1/evaluations` requires READ and uses its shared quota. It returns stored evaluation objects in ascending `(acceptedAt,id)` order; no new fraud decisions are made. Dates apply to acceptance time with `[acceptedFrom,acceptedTo)` bounds. Unknown, duplicate, empty and malformed parameters return 400; out-of-range page/date limits return 422. A cursor binds scope, explicit filters and page size, so repeat those unchanged. Omitted date defaults stay pinned to the first request. Late commits can fall behind a cursor; this is not an export or reconciliation snapshot.

`GET /api/v1/rules` requires POLICY and has a separate quota. It returns one active policy hash with all rule settings and enabled states. Live activation changes the next inspection without an app restart; an already captured response may complete under the previous version. Infrastructure settings, credentials and source YAML are excluded. The HTTP endpoint cannot activate a policy.

`init-secrets.sh` creates and preserves the ignored `secrets/fraud.cursor-signing-key`: 32 random bytes encoded as hexadecimal. Compose mounts it into the runtime configuration tree; local JVM runs must supply `fraud.cursor-signing-key` securely. There is no fallback signing key. All replicas need the same key. Key changes require coordinated restarts and invalidate existing cursors with 400; restart traversal from the first page. Business policy activation neither changes this key nor restarts the application. Keep cursors out of public diagnostic material even though they are not authentication credentials.

## API documentation

Swagger is available at `/swagger-ui/index.html` with the local sample; `/openapi.yaml` serves the packaged [API contract](../api/openapi.yaml). Choose **Authorize** and use the key from `secrets/local-api-key`. Authorization is cleared on page reload. The assets are pinned and served locally, with no remote validator. Requests made through Swagger still require the usual permissions and quotas.

Set `api.documentationEnabled: false` in startup configuration and restart to disable public GET access to both routes. Decide how documentation should be accessed before exposing the service externally. No API key is embedded in the page or mounted into the application container.

## Probes and diagnosis

| Probe | Success | Failure | Meaning |
| --- | --- | --- | --- |
| `GET /health/live` | 200 `{"status":"UP"}` | Unreachable during termination | Process can serve HTTP; no shared dependency checks |
| `GET /health/ready` | 200 `{"status":"UP"}` | 503 `{"status":"DOWN"}` | Schema marker v8, a valid compatible active database policy and authenticated Redis ping succeed |

Probes expose no dependency names or diagnostics. Readiness verifies policy compatibility but does not authorize business traffic. Transaction POST and event GET enforce admission and execute the implemented flow. Evaluation pages and live policy inspection enforce their scoped READ/POLICY permissions, shared quotas and configured query bounds. Default login, diagnostic and unknown routes remain denied. Health routes belong on a restricted operational network when deployed externally.

If readiness fails, inspect `docker compose ps -a`, database/Redis health and application logs. PostgreSQL acquisition is bounded to 500 ms, statements to 1.5 seconds, and Redis connections/commands to 250 ms. These are individual dependency limits, not an overall fraud-request deadline. Policy-administration CLI processes initialize their first database connection synchronously before executing a command, using the validated pool-acquisition value for Hikari initialization. Runtime request pools retain asynchronous initialization and their existing acquisition bound. JDBC network reads are bounded to two seconds. Migration statements have a separate 30-second limit and a 35-second network-read budget. Public readiness recovers after dependencies return; liveness stays independent.

If migration exits nonzero, Compose blocks application startup. Inspect the migration log, resolve the underlying SQL/credential/schema problem, then rerun `docker compose up -d --no-build`. Never edit an applied migration or erase its history to force startup. Keep the existing data volume; use a reviewed forward migration for schema fixes. The isolated logical-restore drill below covers the local backup boundary; off-host backups and arbitrary rolling-release compatibility remain deployment acceptance work.

## Testcontainers Docker context

Maven integration tests need JDK 21 and access to Docker. If your Docker context uses a non-default Unix socket, export it before running the Maven command from [Testing](TESTING.md#run-the-tests):

```sh
export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

These settings describe the local Unix-socket setup. If Docker is unavailable, integration tests fail rather than silently skipping.

## Operational verification

The verification harness requires Python 3.10+ (standard library only), Docker Compose and a local Unix-socket Docker engine. Build the image first, then run:

```sh
python3 -B -m unittest discover -s scripts/verification -p 'test_*.py'
python3 -B scripts/verify-operations.py --suite recovery
python3 -B scripts/verify-operations.py --suite upgrade
python3 -B scripts/verify-operations.py --suite capacity
```

`--suite all` runs recovery and capacity. `--suite upgrade` additionally qualifies the last pinned Debian PostgreSQL image as the source and the current Alpine image as the restore target. It verifies rejection of direct legacy-volume reuse and preservation of Unicode event facts, original responses, table fingerprints, ownership and privileges across the logical restore. New clusters use PostgreSQL's built-in `C.UTF-8` provider; fingerprint ordering explicitly uses `COLLATE "C"` so comparisons do not depend on host locale.

Each run creates a separate `fraud-ops-*` Docker project with its own credentials and temporary loopback ports. It removes those containers, networks, volumes and credentials when it finishes or handles an interruption. The development project is left alone. Do not run qualification workloads alongside other tests or scans that consume CPU/disk; record host contention when interpreting results.

The harness targets a local engine.

Its commit-fault proxy runs in a pinned, non-root, resource-bounded Python sidecar on the disposable Compose network, with no published port or host-gateway dependency. Controls use Docker exec and private tmpfs flags; the proxy receives no mounted credentials and logs no wire payloads. Both relay connections use `TCP_NODELAY` to avoid Nagle/delayed-ACK delays during database startup. TLS is disabled only for that isolated fault connection. It drops one PostgreSQL `CommandComplete(COMMIT)` response, then verifies UNKNOWN, one committed row and identical replay. This tests database commit failures; HAProxy handles HTTP traffic separately.

Recovery checks stop dependencies, saturate blocked writes, kill an app during a real uncommitted write and after commit, discard a caller's HTTP response, drain an in-flight write through SIGTERM, check incompatible-schema readiness and rerun migrations. Timeout checks assert no accepted state, no idle transactions and successful subsequent work. They verify actual financial state after faults rather than treating HTTP errors as proof of rollback.

Restore uses a custom-format logical dump and a separate PostgreSQL volume, restores object ownership/ACLs and verifies consistency and original retrieval. A producer manifest includes an acknowledged event accepted after the dump: it is absent in the restored copy, then replayed exactly once. This demonstrates an explicit backup boundary, not zero data loss or PITR. Restoration of real credentials, encryption keys, external policy governance and off-host backups requires a deployment-specific procedure. Replayed missing events receive new acceptance-time evaluations; the original lost decision is not reconstructed.

[Verification configuration](../config/verification.example.json) contains workload parameters and synthetic event facts, separate from live fraud policy and startup service settings. Capacity uses 100,000 directly seeded historical events for 10,000 scoped customers and four principals. Seed request hashes use canonical v1 identities; seed decisions are explicitly labelled synthetic fixtures, not engine outputs. Every measured new event passes through the real API with all five rules enabled.

The configured small card-present payment exercises history/travel lookup but does not exercise the large-payment sequence's aggregate path; rule-boundary and five-rule smoke tests cover that path separately. The workload is deliberately reproducible and is not a worst-case banking traffic model.

One- and two-instance profiles each offer 20 requests/second for five minutes after warm-up. Hot-customer and read/write profiles run for one minute, followed by bounded overload and target-rate recovery without app restart or quota reset. Requests alternate directly between instances; this tests shared state, not a production load balancer or autoscaler. Existing principal quotas are retained. Replica pools plus rollout surge must fit the declared application connection budget.

Each run writes `target/operations/<run>/results.json` incrementally, including pass/failure state, source/image/configuration/policy identity, hardware/Docker limits, arrivals, statuses, latency and financial invariants. Generator backlog is bounded; late or unsent arrivals count as drops. Latency starts at scheduled arrival and includes queue delay; denied/error responses remain in overall percentiles.

Resource samples record Docker CPU/memory and PostgreSQL connections/activity/lock waits, not unobserved Hikari borrow latency or application queue depth. Keep generated reports out of Git. A custom `--config` is recorded as nonstandard; short development runs do not qualify the default benchmark. A failed target is recorded as a failure, even if a later run passes.

CI runs helper tests and recovery checks alongside the Java/Docker suite. Manual workflow dispatch can additionally run capacity; CI retains `results.json` evidence and, on failure, bounded logs redacted for the generated test passwords and API keys. The proxy-path logs are captured before restoring the direct connection so cleanup cannot replace the original failure context. Only disposable synthetic workloads belong in this harness; raw security secret findings are never uploaded. Run capacity on a quiet, identified host before making throughput claims.

## Load-balanced Docker demonstration

[Scaling](SCALING.md) explains the optional overlay and automatic Docker demonstration. `python3 -B scripts/demo-scaling.py --verify` creates a separate project with one HAProxy entrypoint and synthetic credentials. Results and redacted failure logs live under ignored `target/scaling/`. It does not modify the development project. The demo controller runs on the host with Docker access; no container receives the Docker socket. Ctrl-C removes its containers, volumes and credentials. HAProxy configuration and demo settings are infrastructure inputs; live database business-policy activation keeps its existing semantics.

## Security verification

Install [Trivy 0.76.0](https://github.com/aquasecurity/trivy/releases/tag/v0.76.0) for the host platform and verify its archive against the release checksums. The CI workflow demonstrates installation for Linux. The scanner downloads its vulnerability database on first use.

Maven generates a CycloneDX runtime dependency inventory under `target/sbom/`, outside the application classes so incremental builds cannot embed a stale inventory. Docker packages it as `/app/bom.json`; the image omits unused Spring Boot packaging tools. The security script verifies SHA-256 coverage of every nested executable library before scanning that inventory. This avoids dependency identification by filename or an optional Java fingerprint database.

```sh
TRIVY_BIN=/path/to/trivy ./scripts/security-check.sh
```

The filesystem scan uses `--offline-scan` for dependency identification so a cold CI Maven cache cannot trigger remote POM resolution or Maven Central rate limiting. The hash-verified packaged CycloneDX inventory remains authoritative for complete runtime dependency coverage; vulnerability-database updates remain enabled. See [Trivy Maven troubleshooting](https://github.com/aquasecurity/trivy/blob/main/docs/guide/references/troubleshooting.md).

The pinned scanner checks source/dependencies/secrets, the exact packaged CycloneDX inventory and the built image OS/secrets. Tomcat 11.0.27 and Jackson 3.1.7 explicitly patch the older Boot-managed baseline; keep these overrides until a compatible managed release includes the fixes. [Tomcat advisories](https://tomcat.apache.org/security-11.html) and [Jackson patch notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7) describe the upstream fixes.

The runtime image also pins the [Ubuntu curl fix](https://ubuntu.com/security/CVE-2026-92392) until the pinned Temurin base incorporates it.

Findings and scanner/database identity remain in ignored `target/security/`. HIGH/CRITICAL findings or scanner errors fail the command; no blanket ignore list or `--ignore-unfixed` suppression is used. Review findings for applicability and remediate before release.

Source scanning excludes generated output, ignored local material and runtime secrets; image scanning includes packaged dependencies and the base OS. These scans complement code review and do not establish penetration-test coverage or complete historical secret detection. Never publish raw secret findings as CI artifacts.

The command always scans the pinned PostgreSQL, Redis and optional HAProxy images as well as the application; CI uses the same full gate. HAProxy has a separate `load-balancer.json` report.

The test-only commit-fault image derives from a pinned [official Python image](https://github.com/docker-library/official-images/blob/master/library/python). Its [Dockerfile](../docker/commit-proxy.Dockerfile) removes unused pip/ensurepip tooling and bundled third-party packages; the proxy needs only stdlib. The resulting image passes the same scanner severity/secret gate with a separate `commit-proxy.json` report. Recovery and scanning reuse a local build tagged by its Dockerfile fingerprint, validate its source label and resolve the same immutable image ID.

Official PostgreSQL 17.11 Alpine 3.24 and Redis 8.2.10 Alpine 3.22 replace the Debian variants, retaining the database/server versions while removing the previously reported OS-package findings and bundled test key.

The scans on 2026-10-16 returned zero HIGH/CRITICAL vulnerabilities and zero secret findings. No vulnerability suppressions or severity overrides are used. See the official [PostgreSQL image definitions](https://github.com/docker-library/official-images/blob/master/library/postgres) and [Redis image definitions](https://github.com/docker-library/official-images/blob/master/library/redis). Scanner/database identity and separate `db.json`/`redis.json` reports remain in ignored `target/security/`; scan results are time-specific, not a permanent security guarantee.

## Database image migration

PostgreSQL data directories are not portable across arbitrary OS, locale or image-family changes. The guard checks a marker written only when this image family initializes a cluster and rejects an unmarked or mismatched existing directory before the official entrypoint can change ownership or start PostgreSQL. Do not add a marker to legacy data or bypass the guard. Preserve the source volume and use a logical dump/restore into an empty target. New clusters use [PostgreSQL's built-in C.UTF-8 locale](https://www.postgresql.org/docs/17/app-initdb.html), avoiding dependence on glibc/musl collation definitions. The schema marker remains 8; no new Flyway migrations or fraud-policy changes are required.

For a coordinated migration of an existing local installation, retain the existing secrets and original source image/volume. Stop external producers and the source application before taking the final dump. The following creates a separate target project and never deletes the source volume:

```sh
umask 077
docker compose build app
docker compose stop app
docker compose exec -T db pg_dump -U postgres -d fraud -Fc > /secure/path/fraud.dump
docker compose -p fraud-upgrade-target up -d --wait --wait-timeout 120 db
docker compose -p fraud-upgrade-target run --rm --no-deps db-bootstrap
docker compose -p fraud-upgrade-target exec -T db pg_restore -U postgres -d fraud \
  --clean --if-exists --exit-on-error < /secure/path/fraud.dump
docker compose -p fraud-upgrade-target exec -T db psql -X -v ON_ERROR_STOP=1 -U postgres -d fraud -c ANALYZE
APP_PORT=18081 docker compose -p fraud-upgrade-target up -d --no-build --wait --wait-timeout 120
```

Choose an unused target project/port and an existing protected backup directory.

Keep ingestion stopped until source/target financial counts and deterministic fingerprints, policy hash/history, credential/role/source permissions and known original responses agree. Rebuild statistics with `ANALYZE` after restoration, verify protected requests/readiness, then deliberately switch producer routing to the target. Restoring into a fresh cluster recreates indexes under its locale; copying physical files or refreshing a collation version alone does not perform that migration. An incomplete restore is a failed target, not a reason to modify the source.

Before routing any writes, rollback means removing only the failed target and restarting the unchanged source application with `docker compose start app`. After target writes begin, stop both writers and reconcile those events before any rollback; do not run two writable copies of the same producer history. The disposable `--suite upgrade` exercises the restore/guard path; it is not an authorization to migrate a real installation automatically.

Redis stores ephemeral abuse-quota state, not financial history. Recreating it can restore token allowance, as documented above; coordinate traffic admission during replacement. Deployment TLS identities must always be generated for that deployment.
