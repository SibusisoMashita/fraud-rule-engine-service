# Fraud Rule Engine Service

[![CI](https://github.com/SibusisoMashita/fraud-rule-engine-service/actions/workflows/verify.yaml/badge.svg?branch=main&event=push)](https://github.com/SibusisoMashita/fraud-rule-engine-service/actions/workflows/verify.yaml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00)](pom.xml)
[![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](compose.yaml)

A Java service that checks card purchases and ATM withdrawals against five configurable fraud rules. It stores each evaluation and explains which rules flagged the transaction. Business settings live in PostgreSQL and can change without restarting the service.

The rules cover high amounts, transaction velocity, cumulative spend, impossible travel, and small payments followed by a large payment. The service accepts **BOOKED outgoing transactions** and flags potential fraud; it does not authorize payments. Examples use synthetic data and illustrative thresholds.

Built with Java 21, Spring Boot, PostgreSQL and Redis. Optional HAProxy ingress supports the local scaling demo. The CI badge shows the published `main` branch; [Testing](docs/TESTING.md) records local results and limits.

## Quickstart

You need Git, Docker with Compose 2.24.4+, OpenSSL, curl and a POSIX shell (such as bash or zsh). Docker Desktop includes Compose. Downloads need internet access. Java, Maven and Python are not needed for this startup path.

On Windows, complete the [WSL2 setup](docs/OPERATIONS.md#windows-with-wsl2) first. All commands below use a POSIX shell; on Windows, run them in the Ubuntu terminal.

On a corporate network that inspects HTTPS (for example Zscaler), do the one-time CA step after cloning and before building. Otherwise, run the commands as they are.

```sh
git clone https://github.com/SibusisoMashita/fraud-rule-engine-service.git
cd fraud-rule-engine-service
./scripts/init-secrets.sh
docker compose build app
docker compose up -d --no-build --wait --wait-timeout 120
curl --fail http://localhost:8080/health/ready
```

Expect `{"status":"UP"}`. Startup creates local credentials, runs migrations and loads the sample policy. Only the API port is published, on loopback; PostgreSQL and Redis stay internal. For port conflicts, startup errors or an existing database, see [Operations](docs/OPERATIONS.md#first-start-and-troubleshooting).

### Corporate networks (TLS inspection)

Skip this step on home/open networks that do not inspect HTTPS. Check the certificate issuer presented for Maven Central:

```sh
openssl s_client -connect repo.maven.apache.org:443 -servername repo.maven.apache.org </dev/null 2>/dev/null | grep 'i:'
```

If the issuer identifies your organisation or its inspection service rather than a public CA (such as Amazon, DigiCert or Let's Encrypt), get the approved organisation root CA and export it as PEM into `certs/`. These public CA names are examples, not a complete list; confirm an unfamiliar issuer with IT before trusting it.

For Zscaler on macOS, after cloning and entering the repository:

```sh
security find-certificate -a -c "Zscaler Root CA" -p > certs/zscaler-root-ca.pem
```

Other organisations can get the root/intermediate CA from IT or their certificate store. Use `*.crt` or `*.pem` files, then continue the quickstart normally. For an earlier failed build, run `docker compose build --no-cache app` before starting the service.

Certificate files are git-ignored and used only by the build-stage JDK. The runtime image keeps its existing trust settings. See [Optional organisation CA certificates](certs/README.md) for details.

### Retrying after a failed start

If a build fails, rebuild successfully before running `docker compose up --no-build`; otherwise the image may not exist.

This repo explicitly uses the Compose project name `fraud-rule-engine-service`. Separate clones share that project's containers and database volume unless you override the name with `-p` or `COMPOSE_PROJECT_NAME`. Their separately generated secrets can disagree with the existing services, causing `/health/ready` to return 503 or Redis to report `WRONGPASS`.

Run `docker compose down` in the other clone first. This stops its services and preserves its data; when reusing the existing volume, keep the original credentials. For a throwaway local setup, run `docker compose down --volumes`, then repeat the startup commands to start clean. The `--volumes` option deletes that project's local database data. Use a distinct project name, separate image tag and available port when keeping two clones running.

## Try the API

Open [Swagger UI](http://localhost:8080/swagger-ui/index.html). Choose **Authorize** and paste the key from `secrets/local-api-key`. Swagger uses the same permissions and quotas as other clients.

Prefer Postman? Import the [collection](api/postman/fraud-rule-engine.postman_collection.json) and [local environment](api/postman/local.postman_environment.json), then follow the short [Postman setup](docs/TESTING.md#use-postman) to run the requests and their checks.

Or send this transaction from your terminal. The example saves its timestamp and payload so you can safely repeat it:

```sh
if [ ! -f secrets/example-transaction.json ]; then
  cat > secrets/example-transaction.json <<EOF
{
  "eventId": "demo-event-001",
  "customerId": "demo-customer", "instrumentId": "demo-card",
  "transactionType": "CARD_PURCHASE", "status": "BOOKED", "channel": "ECOMMERCE",
  "amount": "25000.01", "currency": "ZAR", "category": "GROCERIES",
  "occurredAt": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
}
EOF
fi
printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
  curl --config - --include -H 'Content-Type: application/json' \
    --data-binary @secrets/example-transaction.json http://localhost:8080/api/v1/transactions
```

With the initial policy, expect **201 Created** and a `FLAGGED` decision from the high-amount rule. Repeat the unchanged request to get **200 OK** and the original result. Changing the payload under the same event ID returns **409 Conflict**. After a timeout, retrieve or replay the same ID and payload.

Retrieve the saved transaction:

```sh
printf 'header = "X-API-Key: %s"\n' "$(cat secrets/local-api-key)" |
  curl --config - --include http://localhost:8080/api/v1/transactions/demo-event-001
```

[OpenAPI](api/openapi.yaml) describes all endpoints. [Operations](docs/OPERATIONS.md#query-access-and-cursor-keys) explains evaluation queries, policy inspection and pagination.

## Change business settings

Edit a working copy of the [sample policy](config/policy.example.yaml), then activate it:

```sh
cp config/policy.example.yaml secrets/my-policy.yaml
# Edit secrets/my-policy.yaml, then run:
./scripts/activate-policy.sh secrets/my-policy.yaml
```

New evaluations use the updated policy without a restart. Each evaluation keeps the version it started with; saved results stay unchanged. Editing the seed file alone does not activate a change. [Rules](docs/RULES.md) explains the settings; [Operations](docs/OPERATIONS.md#business-policy-live-database-configuration) covers activation and rollback. Infrastructure [service limits](config/service.example.yaml) require a restart.

The optional [administrator API](docs/OPERATIONS.md#optional-policy-administration-api) uses a separate credential and is disabled in the normal quickstart.

## Run tests

For Java tests, install JDK 21, set `JAVA_HOME` and keep Docker running. Maven is included through the wrapper:

```sh
./mvnw -B -ntp verify
docker compose build app
./scripts/smoke.sh
```

Maven runs unit, contract and real-database integration tests. The smoke script checks the built image in a separate Docker project and cleans up afterward. See [Testing](docs/TESTING.md#run-the-tests) for prerequisites, ports and troubleshooting.

## Watch automatic scaling

After building the app image, run this demo with Python 3.10+:

```sh
python3 -B scripts/demo-scaling.py
```

Watch its project in Docker Desktop. Traffic triggers **1 → 2** ready app instances; quiet traffic triggers a drained **2 → 1**. The demo creates its own data and removes its Docker project afterward. [Scaling](docs/SCALING.md) explains the load balancer, settings and extra checks. This is a local demonstration; managed production autoscaling is future work.

## Stop the service

```sh
docker compose down
```

This stops the local stack and keeps the database. Running the startup command again preserves saved evaluations, policy and identities.

## Read more

| Document | What you will find |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Design decisions, data flow and security boundaries |
| [Rules](docs/RULES.md) | The five rules, their settings and how to add another |
| [API reference](api/openapi.yaml) | Requests, responses and permissions |
| [Operations](docs/OPERATIONS.md) | Configuration, credentials, troubleshooting and recovery |
| [Testing](docs/TESTING.md) | Test coverage, measured results and remaining limits |
| [Scaling](docs/SCALING.md) | Readiness routing and the Docker scaling demo |
| [Delivery plan](docs/DELIVERY_PLAN.md) | Completed milestones and future work |
