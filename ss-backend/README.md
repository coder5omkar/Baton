# ss-backend

Spring Boot implementation of the durable message exchange platform described in
`../SPRING-BOOT-BACKEND-DESIGN.md`.

This first slice implements the **publish acceptance path** and the
**transactional outbox dispatcher**:

- CloudEvents 1.0 envelope validation and inline payload limit.
- Canonical content hash for idempotency.
- `SELECT ... FOR SHARE` admission gate, ready for exclusive activation.
- One SQL transaction writes the message, the expected subscriber snapshot, and
  the publish outbox row before returning `202 Accepted`.
- Same message ID + same content returns the original acceptance.
- Same message ID + different content returns `409 MESSAGE_ID_CONFLICT`.
- Publication status endpoint.
- Outbox dispatcher with `SKIP LOCKED`, lease, fence, backoff, and blocked state.
- Logging broker adapter for local development (no Azure required yet).
- Flyway schema and a local-development seed.

## Modules

| Module | Purpose |
|---|---|
| `platform:platform-kernel` | Canonical JSON, hashing, shared primitives |
| `exchange:exchange-domain` | Envelope validation, domain records, exceptions |
| `exchange:exchange-persistence` | jOOQ repositories and Flyway migrations |
| `exchange:exchange-api` | REST API, security, acceptance transaction |
| `exchange:exchange-worker` | Outbox dispatcher and broker port |

## Prerequisites

- JDK 21 or newer. The build targets Java 21 bytecode.
- Docker Desktop for the local PostgreSQL container and integration tests.

## Run locally

Start PostgreSQL (published on host port `15432` to avoid clashing with a
host-installed PostgreSQL):

```bash
docker compose up -d postgres
```

Start the API (local profile listens on `18080`):

```bash
./gradlew :exchange:exchange-api:bootRun --args="--spring.profiles.active=local"
```

Start the worker in a second terminal:

```bash
./gradlew :exchange:exchange-worker:bootRun --args="--spring.profiles.active=local"
```

Publish a message:

```bash
curl -i -X POST http://localhost:18080/v1/flows/orders/messages \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-dev" \
  -d '{
    "specversion": "1.0",
    "id": "ord-2026-000123",
    "source": "billing",
    "type": "order.created.v2",
    "data": {"orderId": "123", "amount": 42.5}
  }'
```

Expected response:

```http
HTTP/1.1 202 Accepted
Location: /v1/flows/orders/publications/ord-2026-000123
X-Acceptance-Time: ...
```

Query publication status:

```bash
curl http://localhost:18080/v1/flows/orders/publications/ord-2026-000123 \
  -H "X-Tenant-Id: tenant-dev"
```

The local seed creates `tenant-dev` / `orders` with two subscribers:
`billing` (webhook) and `analytics` (pull). The worker logs the broker send and
marks the outbox row `PUBLISHED`.

## Tests

Unit tests:

```bash
./gradlew test
```

Integration tests (Testcontainers PostgreSQL, requires Docker):

```bash
./gradlew integrationTest
```

The integration test class is tagged `integration` and skipped by the normal
`test` task.

## Local vs production security

- `local` profile: all requests are permitted and the tenant comes from the
  `X-Tenant-Id` header (default `tenant-dev`).
- Other profiles: the API is an OAuth2 resource server. The configured
  issuer/audience are validated, a `tenant_id` (or `tid`) claim is required, and
  publish/status calls require the `exchange.publish` scope.

## Next slices

1. Flow-specific JSON Schema validation from `catalog_schema`
   (`422 SCHEMA_VALIDATION_FAILED`).
2. Polling lease/renew/settle endpoints and the `delivery` state machine.
3. Webhook bridge with HMAC signing, retry budgets, and quarantine.
4. Receipt ingest, immutable history, and processing projection.
5. Service Bus adapter and dynamic receiver/stager.
6. Replay operations and DLQ recovery.
7. Control plane (`control-api`, Temporal lifecycle worker, Terraform jobs).
