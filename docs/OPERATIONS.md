# Operations

This runbook covers the local runtime foundation. It is not a deployment approval for real financial data. Initial setup and test commands are in the [README](../README.md).

## Lifecycle

```sh
docker compose ps -a
docker compose logs --tail=100 app migrate
docker compose stop app
docker compose up -d --no-build
docker compose down
```

`stop app` sends SIGTERM. Spring drains requests for up to 30 seconds; Compose allows 45 seconds before termination. `down` preserves the PostgreSQL volume. Redis is disposable and currently carries no business state. Container CPU, memory and process limits are defined in [Compose](../compose.yaml).

To discard this local database deliberately, `docker compose down --volumes` removes the project's data volume. It is not a normal restart or migration command.

## Configuration and secrets

[application.yaml](../src/main/resources/application.yaml) contains foundation defaults; Compose supplies database/Redis addresses and mounted secret files. Spring's `configtree` reads the runtime database and Redis passwords. The migration container receives a different database password under the same configuration property. Runtime has schema usage and read access to the version marker, with no DDL, migration history access or migration-role membership. Later business migrations will grant only their required DML privileges.

`init-secrets.sh` generates 256-bit random local passwords without printing them. On the host, the secret directory is owner-only (0700); files inside are read-only (0444) so unprivileged container users can read their individual mounts. The Docker daemon and host owner remain trusted. Database/Redis containers never receive all application secrets. Files are excluded from Git and the Docker build context. Redis currently permits connection commands only; business key/script permissions belong to the later quota implementation.

PostgreSQL initialization runs only on an empty data volume. Editing secret files alone does not rotate existing database passwords; coordinate database role changes and mounted credentials, then recreate clients. Secret-manager integration and rotation automation remain future deployment work.

## Probes and diagnosis

| Probe | Success | Failure | Meaning |
| --- | --- | --- | --- |
| `GET /health/live` | 200 `{"status":"UP"}` | Unreachable during termination | Process can serve HTTP; no shared dependency checks |
| `GET /health/ready` | 200 `{"status":"UP"}` | 503 `{"status":"DOWN"}` | PostgreSQL schema marker v1 and authenticated Redis ping succeed |

Probes expose no dependency names or diagnostics. Foundation readiness does not yet validate a fraud policy or authorize traffic. All other routes are denied, including default login and diagnostic routes. Health routes belong on a restricted operational network when deployed externally.

If readiness fails, inspect `docker compose ps -a`, database/Redis health and application logs. PostgreSQL acquisition is bounded to 500 ms, statements to 1.5 seconds, and Redis connections/commands to 250 ms. These are individual dependency limits, not an overall fraud-request deadline. JDBC network reads are bounded to two seconds. Migration statements have a separate 30-second limit and a 35-second network-read budget. Public readiness recovers after dependencies return; liveness stays independent.

If migration exits nonzero, Compose blocks application startup. Inspect the migration log, resolve the underlying SQL/credential/schema problem, then rerun `docker compose up -d --no-build`. Never edit an applied migration or erase its history to force startup. Keep the existing data volume; use a reviewed forward migration for schema fixes. Backups, restore drills and rolling-release compatibility are later acceptance work.
