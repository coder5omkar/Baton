# Spring Boot Backend Design — Durable Message Exchange Platform

**Source:** 36 architecture screenshots in `D:\project\ss` (design-review deck).
**Status:** Proposed design. No application code exists yet.
**Audience:** Backend/platform engineering.

---

## 0. What the screenshots describe

The screenshots specify a multi-tenant, event-driven **message exchange platform** hosted on Azure. It has two clearly separated planes:

- **Data plane** — publishers submit messages over HTTPS; the platform durably accepts them, fans them out through a broker, and delivers them to subscribers. Subscribers can consume in one of three ways: directly from the broker, by webhook, or by HTTP polling. The platform records delivery custody and authenticated business receipts, and supports targeted replay from DLQ/quarantine.
- **Control plane** — teams self-register flows and subscribers, submit revisions for validation and approval, provision the real infrastructure from a reviewed Terraform plan, verify it, and activate an immutable runtime configuration. Every decision and operation is audited.

The central promise is **durable acceptance**: an HTTP `202 Accepted` is returned only after one SQL transaction has persisted the message, the expected subscriber snapshot, and the publish outbox. From that point the platform owns the message until the recovery horizon. Broker acknowledgement, delivery to the subscriber, and the subscriber's business outcome are three separate facts with three separate state machines.

The reference hosting is Azure (API Management, Container Apps, PostgreSQL, Service Bus, Durable Functions, Key Vault, Entra ID, Terraform), but the public contract and ownership model are intentionally broker- and hosting-independent.

### 0.1 Requirements extracted from the screenshots

| Concern | Requirement in the screenshots | Backend consequence |
|---|---|---|
| Platform shape | Separate control plane (governance/resource lifecycle) and data plane (message custody/delivery) | Separate databases, identities, and deployables |
| Publish acceptance | `202` only after one SQL transaction persists message + expected set + outbox | Transactional outbox, no broker I/O inside the request transaction |
| Idempotency | Stable logical message ID + canonical request hash | Unique key + hash compare; same content returns original acceptance, different content returns `409` |
| Broker | Azure Service Bus first; public contract independent of broker | `MessageBrokerPort` abstraction with a Service Bus adapter |
| Topology | Topic per environment/tenant/flow; subscription per logical subscriber; separate replay topic | Deterministic transport-name mapping, immutable per activation |
| Consumer modes | Direct broker, webhook, or HTTP polling; exactly one active mode per subscriber | `SubscriberBinding.mode`; activation validates one mode |
| Delivery custody | Stage delivery in SQL, then settle broker message; leases and fencing for HTTP | `delivery` work table + peek-lock receiver + fence/version checks |
| Failure handling | Later HTTP failures go to SQL quarantine, not broker DLQ; broker DLQ only for direct consumers | Separate quarantine state and replay ledger |
| Business outcome | Authenticated per-subscriber receipts, independent of broker/HTTP settlement | Immutable `receipt_history` + eventually consistent projection |
| State machines | Publication, delivery, business processing are independent | Explicit state enums and guarded transitions |
| Recovery | Target-specific replay; stable operation/transport IDs; preserve logical identity; consumer inbox protection | `replay_operation`, replay topic with exact subscriber filter, generation-aware dedupe |
| Control plane | Draft → validate → approve exact revision → provision → verify → activate → suspend/drain/retire | Durable workflow + approval digests + audit |
| Provisioning | Exact reviewed Terraform plan; plan-only vs apply-only jobs; scoped identities; protected state | Ephemeral IaC jobs; the Spring app never runs Terraform in-process |
| Activation | Verify access/routing, stage immutable config, exclusive SQL gate, runtime acknowledgement | Internal activation endpoint with `FOR UPDATE` gate |
| Defaults | 128 KiB inline messages, 7-day broker TTL, 30-day recovery horizon | Configurable limits and retention jobs |
| Deployment | APIM, private ACA apps, private PostgreSQL/Service Bus, managed identities, Key Vault | No secrets in config; private ingress; UAMI everywhere |
| Delivery model | At-least-once; idempotent consumers | Stable IDs, inbox/dedupe, documented contracts |

---

## 1. Goals and non-goals

### Goals

1. **Correctness under crashes and retries.** A client timeout must not create two logical messages; a worker crash must not lose custody.
2. **Per-subscriber isolation.** One slow or broken subscriber must not delay another.
3. **Operable recovery.** An operator can replay exactly one subscriber's failed messages without waking unrelated subscribers.
4. **Governed change.** No flow becomes active without a reviewed, immutable revision and a verified infrastructure plan.
5. **Portability.** The message contract and domain model do not depend on Service Bus or Azure.
6. **Simple operations.** Four deployables, two databases, one broker namespace, one workflow engine.

### Non-goals

- Building a broker or a workflow engine.
- Exactly-once end-to-end delivery. The design is **at-least-once + idempotent consumers**.
- Global ordering by default. Ordering is opt-in via broker sessions.
- Multi-region active-active in v1.
- Payloads larger than 128 KiB inline. Add a claim-check pattern later if needed.
- Replacing API Management or Terraform.
- A microservice per box in the architecture diagrams.

---

## 2. Architecture at a glance

Four Spring Boot deployables, matching the four workloads in the deck:

```
Publishers / polling consumers
        │ HTTPS + Entra token
        ▼
   API Management  ──────────────►  exchange-api
                                        │  one TX: gate + message + expected + outbox
                                        ▼
                                  PostgreSQL (exchange)
                                        ▲
                                        │ SKIP LOCKED claims / leases
                              exchange-worker
                                 ├─ outbox dispatcher ──► Service Bus topic per flow
                                 ├─ broker receiver/stager ◄── subscriptions
                                 ├─ webhook sender ──► subscriber HTTPS
                                 ├─ pull lease/renew/settle (API side)
                                 ├─ receipt reducer / projections
                                 └─ replay / DLQ recovery ──► replay topic

Portal SPA ──► APIM ──► control-api ──► PostgreSQL (control)
                              │ control outbox
                              ▼
                     lifecycle-worker (Temporal)
                        ├─ plan job      (ACA Job, read-only identity)
                        ├─ apply job     (ACA Job, scoped writer identity)
                        ├─ verification  (positive + negative access checks)
                        └─ activation ──► exchange-api /internal/activations
```

### 2.1 The four deployables

| Deployable | Owns | Public ingress | Database role |
|---|---|---|---|
| `exchange-api` | Publish acceptance, status, polling lease/renew/settle, receipts, replay requests, internal activation | Via APIM only | `exchange_api` |
| `exchange-worker` | Outbox dispatch, broker receive/stage, webhook send, projections, replay/recovery | None | `exchange_worker` |
| `control-api` | Flow catalog, revisions, reviews/decisions, operations, audit, runtime catalog | Via APIM only | `control_api` |
| `lifecycle-worker` | Durable approval gates, plan/apply orchestration, verification, activation | None | `lifecycle_worker` |

Why not more services: the deck explicitly says logical components do not require a microservice, and PostgreSQL is the durable store for both business workflow records and exchange work. Splitting the API from the worker is justified because they have different scaling, identity, and failure profiles. Splitting the control plane from the data plane is justified because they have different databases, permissions, and release cadence.

### 2.2 Technology choices

| Concern | Choice | Why |
|---|---|---|
| Language/runtime | Java 21 LTS | Virtual threads, records, pattern matching, long support window |
| Framework | Spring Boot 4.x (3.5 LTS is a safe fallback) | Mature security, JDBC, observability, testing |
| HTTP model | Spring MVC + virtual threads | Transactional clarity with blocking JDBC; no reactive complexity on the hot path |
| Hot-path persistence | jOOQ + plain SQL | `SKIP LOCKED`, `ON CONFLICT`, CTEs, fences, typed records |
| Control-plane persistence | jOOQ or Spring Data JPA | CRUD-heavy, optimistic locking, auditable aggregates |
| Migrations | Flyway, expand/contract | Rolling deploys, schema compatibility checks |
| Database | PostgreSQL 16+ | Transactions, row locks, partial indexes, JSONB, RLS |
| Broker | Azure Service Bus (native SDK) | Peek-lock, DLQ, sessions, SQL filters, per-subscription isolation |
| Workflow | Temporal (Cloud or self-hosted) | Durable timers, retries, signals, testable code-first workflows |
| Edge | Azure API Management | Token validation, quotas, request limits, private backend |
| Identity | Entra ID + user-assigned managed identities | No secrets; separate runtime/governance identities |
| Secrets | Key Vault | Webhook signing keys, protected inputs |
| IaC | Terraform in ephemeral ACA Jobs | Plan/apply separation; the app never runs Terraform |
| Observability | Micrometer + OpenTelemetry + Azure Monitor | Traces across async boundaries, RED/USE metrics |
| API docs | springdoc-openapi + JSON Schema | Contract tests and consumer onboarding |

**Workflow alternative:** if the organization requires an Azure-native stack, Azure Durable Functions can replace Temporal. Keep the workflow behind a `LifecycleWorkflowPort` so the choice is reversible. If business users need BPMN modeling, Camunda 8 is the alternative. Temporal is recommended for code-first durability, testability, and signal/timer semantics.

---

## 3. Module and package layout

A Gradle (or Maven) multi-module build. Each deployable is a Spring Boot application; shared code is a thin kernel, not a god-module.

```
ss-backend/
├─ platform/
│  ├─ platform-kernel/          # envelope, canonical JSON, errors, security, tenancy, OTel
│  └─ platform-test/            # Testcontainers, fixtures, clock/lease helpers
├─ exchange/
│  ├─ exchange-domain/          # publish, delivery, receipt, replay, catalog
│  ├─ exchange-api/             # REST adapters, security, activation endpoint
│  └─ exchange-worker/          # dispatchers, receivers, webhook sender, reducers
├─ control/
│  ├─ control-domain/           # flow, revision, review, provisioning, audit
│  └─ control-api/              # REST adapters, ETags, decisions, catalog
├─ lifecycle/
│  └─ lifecycle-worker/         # Temporal workflows + activities, IaC job client
├─ infra/
│  └─ terraform-runner/         # pinned container image for plan/apply jobs
└─ docs/
   └─ adr/                      # architecture decision records
```

Package by feature inside each app, for example:

```
com.acme.exchange
├─ publish/        # PublishService, PublishController, idempotency, schema validation
├─ delivery/       # lease, renew, settle, webhook dispatch, quarantine
├─ receipt/        # receipt ingest, history, projection reducer
├─ replay/         # replay request, executor, DLQ observer
├─ catalog/        # local active catalog, checksums, transport bindings
├─ security/       # JWT converter, tenant binding, flow gate
└─ persistence/    # jOOQ repositories, transaction helpers
```

Use Spring Modulith and ArchUnit to enforce that `publish` cannot call `delivery` internals and that the domain never depends on web adapters.

---

## 4. Domain model and state machines

### 4.1 Publication (per logical message)

```
ACCEPTED ──► PUBLISHING ──► PUBLISHED
                  │
                  └────────► BLOCKED (operator action required)
```

- `ACCEPTED` is represented by the committed `message` row plus a `PENDING` outbox row.
- `PUBLISHING` is a claimed outbox row with a live lease/fence.
- `PUBLISHED` means the broker acknowledged durable publication.
- `BLOCKED` means a permanent error (bad topic, authorization, payload) that requires an operator.

### 4.2 Delivery (per message × subscriber × generation)

```
STAGED ──► LEASED ──► DELIVERED
   │           │
   │           └──────► STAGED (lease expired or released; retry)
   └──────────────────► QUARANTINED (attempt budget exhausted or permanent failure)
```

- Direct subscribers are `BROKER_OWNED` in the per-subscriber ledger; the platform observes their DLQ but does not own their lock.
- Webhook and polling subscribers use SQL-staged delivery rows with leases and fences.

### 4.3 Business processing (per message × subscriber × generation)

```
NOT_REPORTED ──► PROCESSING ──► SUCCEEDED
       │              │
       │              └──────► UNKNOWN_FAILED
       └─────────────────────► SUCCEEDED (late authenticated report)
```

- `SUCCEEDED` is terminal within a generation.
- Late reports never downgrade success.
- A new replay generation is a new processing row; the consumer decides whether to re-apply business effects.

### 4.4 Flow lifecycle (control plane)

```
DRAFT
  │ submit
  ▼
VALIDATING ──► CHANGES_REQUESTED
  │  │
  │  └───────► REJECTED
  ▼ valid
PENDING_APPROVAL
  │ all required decisions approve the same digest
  ▼
APPROVED
  │
  ▼
PROVISIONING ──► OPERATION_FAILED (break-glass / repair / reconcile)
  │
  ▼
VERIFYING
  │ positive + negative checks pass
  ▼
ACTIVE ──► SUSPENDED ──► ACTIVE
  │
  ├────► DRAINING ──► RETIRED
  └────► RETIRED
```

Rules:

- Revisions are immutable. A change creates a new revision with new digests.
- Approval is bound to exact digests: spec, policy, module lock, and saved-plan.
- Any digest change invalidates the approval and requires a new preview.
- Every transition writes an append-only audit event.
- Activation is an exclusive gate; suspension/drain also take the gate.

---

## 5. Data model

Two PostgreSQL databases (or two schemas with separate roles): `exchange` and `control`. They are never joined in a query. The control plane publishes an immutable runtime bundle to the exchange plane; the exchange plane keeps a local read model for admission.

### 5.1 Exchange schema — core DDL

```sql
-- Admission gate. This row is the authority for active/suspended and the active revision.
create table flow_gate (
    tenant_id        text        not null,
    flow_id          text        not null,
    state            text        not null check (state in ('ACTIVE','SUSPENDED','DRAINING','RETIRED')),
    active_revision  bigint,
    generation       bigint      not null default 0,
    catalog_checksum text        not null,
    updated_at       timestamptz not null default now(),
    primary key (tenant_id, flow_id)
);

-- Immutable accepted message. Retention: 30 days by default.
create table message (
    tenant_id      text        not null,
    flow_id        text        not null,
    message_id     text        not null,
    revision       bigint      not null,
    content_hash   text        not null,
    envelope       jsonb       not null,
    headers        jsonb       not null default '{}'::jsonb,
    route_revision bigint      not null,
    accepted_at    timestamptz not null default now(),
    expires_at     timestamptz not null,
    primary key (tenant_id, flow_id, message_id)
);
create index message_expiry_idx on message (expires_at);

-- Expected/actual ledger: one row per message x subscriber x generation.
create table message_subscriber (
    tenant_id        text        not null,
    flow_id          text        not null,
    message_id       text        not null,
    subscriber_id    text        not null,
    generation       bigint      not null default 0,
    binding_revision bigint      not null,
    delivery_mode    text        not null check (delivery_mode in ('DIRECT','WEBHOOK','PULL')),
    dispatch_state   text        not null check (dispatch_state in ('EXPECTED','STAGED','DELIVERED','QUARANTINED','BROKER_OWNED')),
    updated_at       timestamptz not null default now(),
    primary key (tenant_id, flow_id, message_id, subscriber_id, generation)
);
create index message_subscriber_sub_idx
    on message_subscriber (tenant_id, subscriber_id, dispatch_state, updated_at);

-- Transactional outbox: one row per logical message. Topic fan-out handles subscribers.
create table publish_outbox (
    id               bigint      generated always as identity primary key,
    tenant_id        text        not null,
    flow_id          text        not null,
    message_id       text        not null,
    state            text        not null check (state in ('PENDING','CLAIMED','PUBLISHED','BLOCKED')),
    attempt          int         not null default 0,
    next_attempt_at  timestamptz not null default now(),
    lease_owner      text,
    lease_expires_at timestamptz,
    fence            bigint      not null default 0,
    transport_id     text,
    traceparent      text,
    last_error       text,
    created_at       timestamptz not null default now(),
    published_at     timestamptz,
    unique (tenant_id, flow_id, message_id)
);
create index publish_outbox_due_idx
    on publish_outbox (state, next_attempt_at)
    where state = 'PENDING';
```

```sql
-- Bridge work item for webhook/poll subscribers only.
create table delivery (
    id               uuid        primary key,
    tenant_id        text        not null,
    flow_id          text        not null,
    message_id       text        not null,
    subscriber_id    text        not null,
    generation       bigint      not null,
    mode             text        not null check (mode in ('WEBHOOK','PULL')),
    state            text        not null check (state in ('STAGED','LEASED','DELIVERED','QUARANTINED')),
    attempt          int         not null default 0,
    next_attempt_at  timestamptz not null default now(),
    lease_owner      text,
    lease_expires_at timestamptz,
    fence            bigint      not null default 0,
    lease_token_hash text,
    max_attempts     int         not null default 10,
    last_error       text,
    created_at       timestamptz not null default now(),
    delivered_at     timestamptz,
    unique (tenant_id, flow_id, message_id, subscriber_id, generation),
    foreign key (tenant_id, flow_id, message_id, subscriber_id, generation)
        references message_subscriber (tenant_id, flow_id, message_id, subscriber_id, generation)
);
create index delivery_due_idx
    on delivery (mode, state, next_attempt_at)
    where state in ('STAGED','LEASED');
create index delivery_sub_idx
    on delivery (tenant_id, subscriber_id, state, next_attempt_at);

create table delivery_attempt (
    delivery_id uuid        not null references delivery (id) on delete cascade,
    attempt     int         not null,
    started_at  timestamptz not null,
    finished_at timestamptz,
    outcome     text        not null check (outcome in ('SUCCESS','RETRYABLE','PERMANENT','TIMEOUT')),
    http_status int,
    error_code  text,
    primary key (delivery_id, attempt)
);
```

```sql
-- Immutable business-outcome history. Source of truth for processing state.
create table receipt_history (
    id            bigint      generated always as identity primary key,
    tenant_id     text        not null,
    subscriber_id text        not null,
    report_id     text        not null,
    message_id    text        not null,
    generation    bigint      not null,
    sequence      bigint      not null,
    outcome       text        not null check (outcome in ('PROCESSING','SUCCEEDED','FAILED','UNKNOWN')),
    occurred_at   timestamptz not null,
    payload       jsonb       not null,
    received_at   timestamptz not null default now(),
    unique (tenant_id, subscriber_id, report_id)
);
create index receipt_history_msg_idx
    on receipt_history (tenant_id, subscriber_id, message_id, generation, sequence desc);

-- Current view for status APIs. Eventually consistent, never downgrades SUCCEEDED.
create table processing_projection (
    tenant_id      text        not null,
    subscriber_id  text        not null,
    message_id     text        not null,
    generation     bigint      not null,
    status         text        not null check (status in ('NOT_REPORTED','PROCESSING','SUCCEEDED','UNKNOWN_FAILED')),
    last_sequence  bigint      not null,
    last_report_id text        not null,
    updated_at     timestamptz not null default now(),
    primary key (tenant_id, subscriber_id, message_id, generation)
);
```

```sql
-- One operator-initiated, target-specific replay.
create table replay_operation (
    id               uuid        primary key,
    tenant_id        text        not null,
    flow_id          text        not null,
    subscriber_id    text        not null,
    requested_by     text        not null,
    reason           text        not null,
    selector         jsonb       not null,
    state            text        not null check (state in ('REQUESTED','CLAIMED','REPLAYING','COMPLETED','FAILED')),
    generation       bigint      not null,
    attempt          int         not null default 0,
    lease_owner      text,
    lease_expires_at timestamptz,
    fence            bigint      not null default 0,
    created_at       timestamptz not null default now(),
    completed_at     timestamptz
);
```

### 5.2 Control schema — key tables

| Table | Purpose | Important columns |
|---|---|---|
| `participant` | Tenant/participant registry | `participant_id`, `display_name`, `identity_bindings`, `status` |
| `flow` | Stable logical flow | `tenant_id`, `flow_id`, `owner_group`, `created_at` |
| `flow_revision` | Immutable revision | `revision`, `spec jsonb`, `policy jsonb`, `module_lock jsonb`, `spec_digest`, `policy_digest`, `module_digest`, `plan_digest`, `state` |
| `subscriber_binding` | Per-revision subscriber config | `subscriber_id`, `mode`, `required`, `supported_versions`, `endpoint_ref`, `secret_ref`, `filter` |
| `change_request` | Reviewable request | `request_id`, `revision`, `state`, `etag`, `submitted_by`, `submitted_at` |
| `approval` | Individual decision | `request_id`, `reviewer`, `role`, `decision`, `digests jsonb`, `decided_at` |
| `provisioning_operation` | Durable operation | `operation_id`, `request_id`, `state`, `attempts`, `scope_snapshot`, `result_manifest jsonb` |
| `provisioning_lock` | One writer per flow | `flow_id`, `operation_id`, `lease_expires_at`, `fence` |
| `runtime_config_bundle` | Immutable activation | `flow_id`, `revision`, `generation`, `routing_revision`, `catalog_checksum`, `bundle jsonb`, `activated_at`, `ack_at` |
| `control_outbox` | Commands to workflow | `id`, `command_type`, `aggregate_id`, `payload`, `state`, `attempt`, `fence` |
| `command_inbox` | Workflow idempotency | `command_id`, `payload_digest`, `received_at` |
| `audit_event` | Append-only audit | `id`, `tenant_id`, `actor`, `action`, `aggregate_type`, `aggregate_id`, `before_hash`, `after_hash`, `payload`, `occurred_at` |

### 5.3 Constraints that enforce the invariants

- `message` primary key `(tenant_id, flow_id, message_id)` makes duplicate logical messages impossible.
- `publish_outbox` is unique per message, so acceptance cannot enqueue twice.
- `message_subscriber` primary key includes `generation`, so replay is explicit and auditable.
- `delivery` is unique per message × subscriber × generation, so two workers cannot create two bridge rows.
- `receipt_history` is unique per `(tenant, subscriber, report_id)`, so a consumer's local outbox can retry safely.
- All lease expiry comparisons use **database time** (`now()`), never application time.
- All fences are monotonically increasing integers; every mutation is conditional on the fence.
- Tenant isolation is enforced twice: application predicates and PostgreSQL row-level security.

### 5.4 Retention and partitioning

| Data | Default retention | Mechanism |
|---|---|---|
| Broker message | 7 days | Service Bus topic/subscription TTL set by IaC |
| Message body (for replay) | 30 days | `message.expires_at` + scheduled purge |
| Outbox / delivery | 30 days after terminal state | Archive then delete |
| Receipt history | Per audit policy (e.g., 1 year) | Monthly partitions, drop old partitions |
| Audit events | Per compliance policy | Append-only, export to immutable storage |

Start unpartitioned. Partition `message`, `message_subscriber`, and `receipt_history` by month when row counts justify it; retention becomes a partition drop instead of a mass delete.

---

## 6. Exchange API

### 6.1 Endpoints

| Method | Path | Scope | Purpose | Success |
|---|---|---|---|---|
| `POST` | `/v1/flows/{flowId}/messages` | `exchange.publish` | Durable acceptance | `202` |
| `GET` | `/v1/publications/{messageId}` | `exchange.publish` | Publication status | `200` |
| `POST` | `/v1/deliveries/lease` | `exchange.consume` | Lease one poll delivery | `200` / `204` |
| `POST` | `/v1/deliveries/{deliveryId}/renew` | `exchange.consume` | Extend a lease | `200` |
| `POST` | `/v1/deliveries/{deliveryId}/settle` | `exchange.consume` | Complete/release/fail | `200` / `409` |
| `POST` | `/v1/receipts` | `exchange.receipt` | Record business outcome | `202` |
| `GET` | `/v1/subscribers/{subscriberId}/status` | `exchange.consume` | Delivery/processing view | `200` |
| `POST` | `/v1/replays` | `exchange.replay` | Request targeted replay | `202` |
| `GET` | `/v1/replays/{operationId}` | `exchange.replay` | Replay status | `200` |
| `POST` | `/internal/activations` | Internal UAMI only | Activate a revision | `200` |

The tenant is derived from the authenticated principal, not from the URL. `flowId` and `subscriberId` are logical IDs; transport names (Service Bus topic/subscription) live in the catalog bundle and never appear in public APIs.

### 6.2 Publish request and response

Request (CloudEvents 1.0 envelope; the payload is immutable):

```json
{
  "specversion": "1.0",
  "id": "ord-2026-000123",
  "source": "billing",
  "type": "order.created.v2",
  "subject": "orders/123",
  "time": "2026-11-01T10:15:30Z",
  "datacontenttype": "application/json",
  "dataschema": "https://catalog.example/order-created.v2.json",
  "data": {
    "orderId": "123",
    "amount": { "currency": "EUR", "value": 42.50 }
  }
}
```

Response:

```http
HTTP/1.1 202 Accepted
Location: /v1/publications/ord-2026-000123
X-Acceptance-Time: 2026-11-01T10:15:30.412Z
Content-Type: application/json

{ "messageId": "ord-2026-000123", "state": "ACCEPTED" }
```

Idempotency rules:

| Situation | Result |
|---|---|
| New `id` | Validate, persist, enqueue, `202` |
| Same `id`, same canonical hash | Return original acceptance (`202`, original `Location` and acceptance time) |
| Same `id`, different hash | `409 MESSAGE_ID_CONFLICT` with the existing acceptance metadata |
| Concurrent duplicate publishes | Unique constraint + `ON CONFLICT`; one wins, the other reads the winner |
| Validation failure | `422 SCHEMA_VALIDATION_FAILED`; nothing persisted |
| Flow suspended/retired | `409 FLOW_NOT_ACTIVE`; nothing persisted |
| Payload > 128 KiB | `413 PAYLOAD_TOO_LARGE`; nothing persisted |
| Backpressure | `503 BACKPRESSURE` with `Retry-After`; never a false `202` |

The canonical hash is computed over the canonical JSON (RFC 8785) of the logical envelope, excluding volatile transport headers. Store both the hash and the original envelope.

### 6.3 Publish acceptance — exact transaction boundary

```java
@Transactional
public Acceptance accept(PublishCommand cmd, CallerContext caller) {
    // 1. Admission lock: many publishers may share; activation takes an exclusive lock.
    gateRepository.lockForAdmission(cmd.tenantId(), cmd.flowId()); // SELECT ... FOR SHARE
    FlowGate gate = gateRepository.get(cmd.tenantId(), cmd.flowId());
    if (!gate.isActive()) {
        throw new FlowNotActiveException(gate.state());
    }
    if (cmd.revision() != null && cmd.revision() != gate.activeRevision()) {
        throw new StaleRevisionException(gate.activeRevision());
    }
    authorization.requirePublish(caller, gate);

    // 2. Idempotency check inside the same transaction.
    Optional<AcceptedMessage> existing = messageRepository.find(cmd.key());
    if (existing.isPresent()) {
        if (!existing.get().contentHash().equals(cmd.contentHash())) {
            throw new MessageIdConflictException(existing.get());
        }
        return Acceptance.replay(existing.get());
    }

    // 3. Validate against the immutable active revision.
    schemaValidator.validate(gate.activeRevision(), cmd.envelope());

    // 4. One local transaction: message + expected subscriber snapshot + outbox.
    messageRepository.insert(cmd, gate);
    messageSubscriberRepository.insertExpected(
        cmd, catalog.subscribers(gate.activeRevision()));
    outboxRepository.enqueue(cmd);

    return Acceptance.accepted(cmd);
}
```

Why `FOR SHARE`:

- Publish transactions share the gate row and can run concurrently.
- Activation/suspension takes `FOR UPDATE`, which conflicts with `FOR SHARE`; it waits for in-flight acceptance transactions to commit, then flips the gate.
- New publishes block behind the activation and see the new state after commit.
- Set `lock_timeout` (e.g., 2 s) so a stuck activation cannot pin the API forever; return `503` with `Retry-After`.

No broker call, no HTTP call, and no workflow call happens inside this transaction.

### 6.4 Polling lease, renew, settle

Lease request:

```json
POST /v1/deliveries/lease
{ "maxMessages": 1, "leaseSeconds": 60 }
```

Response `200`:

```json
{
  "deliveryId": "3f0c...",
  "leaseToken": "opaque-one-time-token",
  "leaseExpiresAt": "2026-11-01T10:16:30Z",
  "attempt": 2,
  "envelope": { "specversion": "1.0", "id": "ord-2026-000123", "data": { } }
}
```

`204 No Content` when no delivery is due.

Claim SQL (one row, `SKIP LOCKED`; the same pattern works for a batch):

```sql
with due as (
    select id
    from delivery
    where tenant_id = :tenant
      and subscriber_id = :subscriber
      and mode = 'PULL'
      and (
            (state = 'STAGED' and next_attempt_at <= now())
         or (state = 'LEASED' and lease_expires_at <= now())
      )
    order by next_attempt_at
    limit 1
    for update skip locked
)
update delivery d
set state            = 'LEASED',
    lease_owner      = :owner,
    lease_expires_at = now() + make_interval(secs => :leaseSeconds),
    lease_token_hash = :tokenHash,
    fence            = d.fence + 1,
    attempt          = d.attempt + 1
from due
where d.id = due.id
returning d.*;
```

- The raw lease token is returned once; only its SHA-256 hash is stored.
- `renew` extends the lease only if `fence` matches, the state is `LEASED`, and the total lease budget (e.g., 15 minutes) is not exceeded.
- `settle` accepts `COMPLETE`, `RELEASE`, or `FAIL`:

```sql
update delivery
set state        = 'DELIVERED',
    delivered_at = now(),
    lease_token_hash = null
where id = :id
  and fence = :fence
  and state = 'LEASED'
  and lease_expires_at > now();
```

Zero rows updated means `409 LEASE_LOST`; the client must not assume its business commit was accepted. `RELEASE` returns the row to `STAGED` with a backoff. `FAIL` retries until `max_attempts`, then quarantines.

Expiry is implicit: an expired `LEASED` row is claimable again. The former token fails the fence check, so it cannot settle a later lease. Consumer inbox dedupe protects business effects across redeliveries.

### 6.5 Receipts

Request:

```json
POST /v1/receipts
{
  "reportId": "9f2c...",
  "messageId": "ord-2026-000123",
  "generation": 0,
  "sequence": 7,
  "outcome": "SUCCEEDED",
  "occurredAt": "2026-11-01T10:16:02Z",
  "detail": { "orderStatus": "CONFIRMED" }
}
```

- The subscriber is bound from the authenticated principal, never from the body.
- `reportId` is stable across retries; the consumer's local receipt outbox reuses it.
- `sequence` is monotonic per `(message, subscriber, generation)`; a lower sequence is stale.
- `SUCCEEDED` is terminal; a later `FAILED` is recorded in history but does not downgrade the projection.

Transaction:

```sql
insert into receipt_history (...) values (...)
on conflict (tenant_id, subscriber_id, report_id) do nothing;

insert into processing_projection (...)
values (...)
on conflict (tenant_id, subscriber_id, message_id, generation) do update
set status         = excluded.status,
    last_sequence  = excluded.last_sequence,
    last_report_id = excluded.last_report_id,
    updated_at     = now()
where processing_projection.status <> 'SUCCEEDED'
  and excluded.last_sequence > processing_projection.last_sequence;
```

Same `reportId` + same payload → `202` with the original receipt ID. Same `reportId` + different payload → `409 REPORT_ID_CONFLICT`. Unknown message/subscriber pair → `404`. Report for an expired retention window → `410 RETENTION_EXPIRED`.

### 6.6 Replay

Request:

```json
POST /v1/replays
{
  "subscriberId": "sub-a",
  "selector": { "messageIds": ["ord-2026-000123"] },
  "reason": "consumer bug fixed in release 42",
  "idempotencyKey": "replay-2026-11-01-001"
}
```

- Requires `exchange.replay` plus an operator role; bulk replay may require a second approval.
- Creates a `replay_operation` with a stable operation ID and a target subscriber.
- The worker reads the original envelope (within the 30-day retention window) and publishes to the flow's replay topic with:
  - `targetSubscriberId` application property, used by an exact SQL filter on the subscriber's replay subscription;
  - `replayGeneration = original + 1`;
  - transport ID `replay:{operationId}:{messageId}`;
  - the original logical envelope ID unchanged.
- Unrelated subscribers are not woken.
- The source DLQ/quarantine item is completed only after the broker acknowledges the replay send. An uncertain send is reconciled or retried; consumer inbox dedupe makes the retry safe.

### 6.7 Error model

Use RFC 9457 Problem Details (`application/problem+json`):

```json
{
  "type": "https://errors.example/message-id-conflict",
  "title": "Message ID already used with different content",
  "status": 409,
  "code": "MESSAGE_ID_CONFLICT",
  "detail": "Message ord-2026-000123 was accepted at 2026-11-01T10:15:30Z with a different payload hash.",
  "instance": "/v1/flows/orders/messages",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "retryable": false
}
```

Codes: `MESSAGE_ID_CONFLICT`, `FLOW_NOT_ACTIVE`, `STALE_REVISION`, `SCHEMA_VALIDATION_FAILED`, `PAYLOAD_TOO_LARGE`, `QUOTA_EXCEEDED`, `BACKPRESSURE`, `LEASE_LOST`, `LEASE_EXPIRED`, `REPORT_ID_CONFLICT`, `STALE_RECEIPT`, `RETENTION_EXPIRED`, `REPLAY_NOT_ALLOWED`.

---

## 7. Exchange worker

The worker has no public ingress. It runs several bounded loops, each with its own claim query, concurrency budget, and metrics.

### 7.1 Outbox dispatcher

```
claim due row (SKIP LOCKED, fence++)
   │
   ├─ send to Service Bus topic with stable MessageId
   │     MessageId = logical message ID
   │     ApplicationProperties: tenantId, flowId, revision, replayGeneration=0
   │
   ├─ broker ACK ──► conditional UPDATE ... WHERE fence = :fence AND state = 'CLAIMED'
   │                   └─ 0 rows: lease was stolen; do nothing
   │
   ├─ transient error ──► state = PENDING, next_attempt_at = now() + backoff + jitter
   │
   └─ permanent error ──► state = BLOCKED, operator evidence, alert
```

Key rules:

- One claimer per flow shard, enforced by `pg_try_advisory_lock`; multiple worker replicas still use `SKIP LOCKED` for rows.
- A send may be duplicated if the worker crashes after the broker ACK but before the SQL update. That is expected; consumers dedupe by `MessageId`.
- The immutable route snapshot (topic, headers, expected subscribers) is stored with the outbox row or message so a later catalog change cannot alter an already-accepted message.
- `traceparent` is stored at acceptance and extracted at dispatch to continue the trace asynchronously.

### 7.2 Broker receiver and SQL staging

For webhook/poll subscribers, the worker receives from each subscription with peek-lock and manual settlement:

```
receive (peek-lock, autoComplete=false)
   │
   ├─ validate routing metadata and replay generation
   ├─ TX: upsert message_subscriber (STAGED) + insert delivery (STAGED) + attempt row
   ├─ commit
   └─ complete broker message
```

- The broker message is settled **only after** the SQL transaction commits durable custody.
- On transient SQL failure, abandon the broker message so it is redelivered.
- On permanent validation failure, dead-letter it with a reason.
- Keep the lock duration short (staging is one small transaction); renew only if staging is unexpectedly slow.
- Prefetch is small (0–1) for fair distribution across subscribers.

Direct subscribers are not handled here. They read their own subscription with their own SDK, commit business effects, and complete the broker message. The platform only observes their DLQ for recovery.

### 7.3 Webhook sender

```
claim due WEBHOOK delivery (SKIP LOCKED, fence++)
   │
   ├─ sign request (HMAC, timestamp, key id, delivery id)
   ├─ POST with connect timeout 2s / read timeout 10s, redirects disabled
   ├─ 2xx ──► DELIVERED
   ├─ 408/425/429/5xx ──► retry with exponential backoff + jitter (honor Retry-After)
   ├─ other 4xx ──► QUARANTINED immediately (configurable)
   └─ budget exhausted ──► QUARANTINED
```

Webhook contract:

- `2xx` means "custody accepted", not "business processed".
- The consumer must dedupe by `X-Exchange-Delivery` (delivery ID).
- Signature headers:

```http
X-Exchange-Delivery: 3f0c...
X-Exchange-Timestamp: 1762078530
X-Exchange-Key-Id: sub-a-2026-11
X-Exchange-Signature: v1=<hex hmac-sha256(secret, timestamp + "." + deliveryId + "." + rawBody)>
```

- Reject timestamps older than 5 minutes; use constant-time comparison; support key rotation by `Key-Id`.
- SSRF protection: HTTPS only; no redirects; resolve and reject loopback/link-local/private/metadata addresses unless the destination is an explicitly registered private endpoint; cap response body size.
- Per-destination bulkhead and circuit breaker so one slow subscriber cannot starve the loop.

### 7.4 Receipt reducer and projections

- Append `receipt_history` first, then apply the projection in the same transaction. History is the source of truth; the projection is a read model.
- If receipt volume grows, split into an append transaction plus a claimable reducer work table; the projection apply must remain idempotent and monotonic.
- Never downgrade `SUCCEEDED`. Late reports are retained in history with a `SUPERSEDED`/stale marker if needed.

### 7.5 Replay and DLQ recovery

```
operator request ──► replay_operation (REQUESTED)
   │
   ├─ claim operation (lease + fence)
   ├─ read original envelope under recovery lease
   ├─ publish to replay topic with exact target filter and replay generation
   ├─ broker ACK
   ├─ complete source DLQ/quarantine item
   └─ mark operation COMPLETED, write ledger/audit
```

- DLQ observation is non-destructive (peek). Observing a message is not permission to replay it.
- The replay operation is target-specific: one subscriber, one generation.
- The original logical ID is preserved; only the transport ID changes.
- If the send is uncertain, retry; the consumer's inbox dedupes.
- Replay is rate-limited and audited; bulk replay can require a second approver.

### 7.6 Concurrency and shutdown

- Java 21 virtual threads for I/O-bound loops; bounded semaphores per flow, per subscriber, and per destination.
- `maxConcurrentCalls` on Service Bus receivers; small prefetch; no unbounded queues.
- Graceful shutdown: stop claiming, finish in-flight work up to a deadline, release or expire leases, close receivers.
- No database transaction wraps a network call.

---

## 8. Control plane

### 8.1 Responsibilities

- Register participants, flows, revisions, and subscriber bindings.
- Validate specs, policies, schemas, and plan previews.
- Collect approvals bound to exact digests.
- Drive plan-only and apply-only infrastructure jobs.
- Verify access and routing with positive and negative checks.
- Activate an immutable runtime bundle; suspend, drain, and retire.
- Expose inventory, operation status, and audit.

### 8.2 Decision transaction and control outbox

A review decision is persisted before the workflow is told anything:

```java
@Transactional
public DecisionResult decide(DecisionCommand cmd, Actor actor) {
    ChangeRequest request = requestRepository.getForUpdate(cmd.requestId());
    request.requireEtag(cmd.ifMatch());
    request.requireState(PENDING_APPROVAL);
    authorization.requireReviewer(actor, request);

    approvalRepository.append(actor, cmd.decision(), cmd.digests());
    request.applyDecision(cmd.decision(), cmd.digests());
    auditRepository.append(actor, request, cmd);

    if (request.isFullyApproved()) {
        controlOutbox.enqueue(StartProvisioning.of(request));
    }
    return DecisionResult.recorded(request);
}
```

- `If-Match` (ETag) prevents lost updates.
- The approval stores the exact spec/policy/module/plan digests. If any digest changes, the approval is invalid.
- The control outbox relay starts or signals the workflow with a stable operation/workflow ID.
- The workflow's command inbox dedupes repeated relay attempts.

### 8.3 Temporal workflow

```java
@WorkflowInterface
public interface FlowLifecycleWorkflow {
    @WorkflowMethod
    FlowLifecycleResult run(FlowLifecycleInput input);

    @SignalMethod
    void decision(DecisionSignal signal);

    @SignalMethod
    void cancel(CancelSignal signal);
}
```

Activities:

| Activity | Responsibility | Idempotency key |
|---|---|---|
| `runPlanJob` | Start plan-only ACA Job; collect saved plan + digest + inventory | `operationId:plan` |
| `runApplyJob` | Start apply-only ACA Job with the exact approved artifact | `operationId:apply` |
| `verifyAccess` | Positive + negative identity/access checks | `operationId:verify-access` |
| `verifyRouting` | Canary publish/consume, schema compatibility, readiness | `operationId:verify-routing` |
| `activateRevision` | Call exchange activation endpoint; record ACK | `operationId:activate` |
| `recordFailure` | Persist failure evidence; optionally notify | `operationId:failure` |

Rules:

- Workflow ID is deterministic: `flow-lifecycle/{tenant}/{flow}/{requestId}`.
- The workflow never trusts a signal payload as the decision; activities re-read authoritative decision state from the control database.
- Timers implement approval SLAs and escalation.
- Activity retries use exponential backoff; permanent failures branch to `OPERATION_FAILED` with a break-glass runbook.
- Workflow versioning is explicit; long-running approvals can span deployments.

### 8.4 Plan-only and apply-only jobs

- The control plane never runs Terraform in-process.
- `runPlanJob` starts a pinned container image with a read-only identity, a reviewed module lock, and the target state key. It produces a saved plan, a digest, and a resource inventory.
- Approval is bound to the saved-plan digest.
- `runApplyJob` starts a separate job with a scoped writer identity and the exact approved artifact. It verifies the state lock and re-reads resource versions before applying.
- Protected inputs (saved plans, state) live in a protected blob container/Key Vault; job logs are redacted.
- One apply per flow at a time: `provisioning_lock` with a lease and fence.
- Plan/apply separation means a changed state, spec, or lockfile requires a new preview, not an unreviewed replay.

### 8.5 Verification and activation

Verification is evidence, not a checkbox:

- Positive checks: an approved publisher can publish; an approved subscriber can lease/receive; routing reaches the right subscription.
- Negative checks: an unapproved identity is denied; a wrong flow/subscriber cannot read another's messages.
- Schema compatibility: the new revision accepts supported versions and rejects unsupported ones.
- Readiness: topic/subscription exists, filters match, DLQ configured, retention/TTL correct.

Activation protocol:

1. Workflow calls `POST /internal/activations` with `operationId`, `flowId`, `revision`, `generation`, bundle URI/digest, and catalog checksum.
2. `exchange-api` locks the gate `FOR UPDATE`, checks that the operation/generation is newer than the current one (idempotent replay returns the existing ACK), stages the immutable bundle, flips the gate, commits, and returns an ACK.
3. The workflow records the ACK in the control database and marks the runtime catalog active.
4. If the ACK is lost, the workflow retries with the same operation ID; activation is idempotent.
5. Suspension/drain/retire use the same gate and audit path.

---

## 9. Security

### 9.1 External identities

- Entra ID OAuth2 client credentials for publishers and consumers; authorization code + PKCE for the portal.
- APIM validates the token, audience, quota, and request size, then forwards to a private backend.
- The Spring app validates the JWT again (defense in depth), checks audience, expiry, tenant/participant binding, and scope.
- Scopes: `exchange.publish`, `exchange.consume`, `exchange.receipt`, `exchange.replay`, `control.read`, `control.write`, `control.approve`, `control.operate`.
- Roles: `FLOW_OWNER`, `SUBSCRIBER_OWNER`, `PLATFORM_ADMIN`, `OPERATOR`, `AUDITOR`. Separation of duties: a requester cannot approve their own request by default.

### 9.2 Internal identities

- User-assigned managed identities per deployable and per job.
- `exchange-api`: PostgreSQL `exchange_api` role, no broker send rights, no Azure management rights.
- `exchange-worker`: `exchange_worker` role, Service Bus data sender/receiver, no control DB access.
- `control-api`: `control_api` role, no exchange payload access.
- `lifecycle-worker`: `lifecycle_worker` role, workflow engine access, ability to start IaC jobs, no direct broker access.
- IaC jobs: separate plan-reader and apply-writer identities; the apply identity is scoped to the approved resource group/flow.
- No secrets in environment variables or config files. Key Vault references + managed identity.

### 9.3 Data protection

- PostgreSQL private endpoint, TLS, Entra authentication, row-level security by `tenant_id`.
- Service Bus private endpoint, managed identity, scoped roles.
- Payloads are redacted from logs; only hashes and metadata are logged.
- Audit events are append-only; optionally hash-chained per aggregate for tamper evidence.
- Webhook signing keys are per-subscription, stored in Key Vault, rotated with a `Key-Id`.
- Replay and bulk operations are audited with actor, reason, selector, and result.

### 9.4 Threat notes

| Threat | Mitigation |
|---|---|
| Duplicate publish from client retry | Stable message ID + canonical hash + unique constraint |
| Spoofed subscriber receipt | Principal-to-subscriber binding from token, not body |
| Webhook replay | Timestamp window + delivery ID dedupe + signature |
| SSRF via webhook URL | HTTPS-only, no redirects, private-IP rejection, allowlist for private endpoints |
| Cross-tenant data access | JWT tenant claim + SQL predicates + RLS |
| Malicious Terraform input | Reviewed module lock, plan digest, protected artifacts, scoped identity |
| Activation race | Exclusive SQL gate; in-flight publishes drain before flip |
| Stolen lease token | Hash at rest, fence check, short lease, total budget |

---

## 10. Observability and operations

### 10.1 Tracing

- W3C `traceparent` is stored with the accepted message and the outbox row.
- The dispatcher extracts it and starts a linked span for the broker send.
- The receiver continues the trace into staging; webhook/poll delivery carries the trace context in headers.
- Receipts continue the consumer's trace if supplied; otherwise start a new one.

### 10.2 Metrics

| Metric | Type | Alert |
|---|---|---|
| `exchange.publish.accept` | timer + outcome | p99 > 300 ms, error rate > 1% |
| `exchange.outbox.lag` | gauge | > 60 s |
| `exchange.outbox.blocked` | gauge | > 0 |
| `exchange.delivery.lease.age` | histogram | p99 > lease budget |
| `exchange.webhook.attempt` | counter + outcome | error rate > 5% |
| `exchange.delivery.quarantine` | gauge | > 0 |
| `exchange.receipt.lag` | histogram | p99 > 5 min |
| `exchange.projection.stale` | gauge | > 0 |
| `exchange.replay.operations` | counter + state | failed > 0 |
| `hikaricp.connections.active` | gauge | > 80% of pool |
| `servicebus.dlq.depth` | gauge | > 0 for direct subscribers |

### 10.3 Health

- Readiness: database reachable, Flyway schema version compatible, catalog checksum loaded, broker client initialized.
- Liveness: process health only; do not fail liveness on a transient dependency.
- Startup: fail fast on incompatible schema; never auto-migrate in production (run a migration job).

### 10.4 Runbooks

- Blocked publication: inspect `last_error`, fix topic/auth, reset to `PENDING` with an audit entry.
- Quarantine replay: verify retention, create a replay operation for one subscriber, watch the ledger.
- Stuck lease: inspect `lease_owner`/`lease_expires_at`; let it expire or force-release with an audit entry.
- DLQ drain: peek, classify, replay or discard with evidence.
- Activation rollback: activate the previous immutable bundle with a new generation; never mutate the old bundle.

---

## 11. Configuration and deployment

### 11.1 Configuration properties

```yaml
exchange:
  message:
    max-inline-bytes: 131072
    retention: 30d
  outbox:
    claim-batch: 50
    lease: 30s
    max-attempts: 20
  delivery:
    lease: 60s
    max-lease-budget: 15m
    max-attempts: 10
  webhook:
    connect-timeout: 2s
    read-timeout: 10s
    max-response-bytes: 64KB
    timestamp-window: 5m
  replay:
    max-batch: 1000
    rate-limit: 100/s
  quota:
    publish-per-second: 500
    in-flight-bytes: 64MB
```

Use Spring profiles per environment/cell (`dev`, `test`, `prod`), but never encode tenant-specific values in config; those live in the catalog bundle.

### 11.2 Deployment mapping

| Screenshot component | Spring Boot deployable | Azure hosting |
|---|---|---|
| Exchange API | `exchange-api` | Container Apps, internal ingress, behind APIM |
| Exchange workers | `exchange-worker` | Container Apps, no ingress, KEDA on Service Bus/DB lag |
| Control portal/API | `control-api` + SPA | Container Apps internal + Static Web Apps |
| Lifecycle workflow | `lifecycle-worker` | Container Apps + Temporal Cloud (or self-hosted) |
| Terraform job | `terraform-runner` | ACA Job, pinned image, UAMI per plan/apply |
| PostgreSQL | — | Flexible Server, private DNS, zone-redundant HA |
| Service Bus | — | Premium namespace, private endpoint, scoped roles |
| Identity | — | Entra ID + user-assigned managed identities |
| Secrets | — | Key Vault, private endpoint |
| Observability | — | Azure Monitor + OTel Collector |

### 11.3 Scaling

- `exchange-api`: stateless; scale on HTTP concurrency and CPU; size the DB pool per replica so `replicas × pool < max_connections`; use PgBouncer transaction pooling if needed (`SET LOCAL` is transaction-scoped and safe).
- `exchange-worker`: scale on outbox lag and subscription depth; cap concurrency per flow and per destination; one claimer per flow shard.
- `control-api`: low volume; scale on CPU.
- `lifecycle-worker`: workflow workers are long-lived; scale by task queue and activity concurrency; keep workflow code deterministic.
- Database: index the claim paths, watch bloat on hot tables, partition when retention deletes become expensive.

### 11.4 Release

- Build once, deploy the same image by digest.
- Flyway migrations run as a separate job using expand/contract: add columns/tables first, deploy code, backfill, remove old columns later.
- Container Apps revisions provide blue/green; readiness gates traffic.
- Workflow versioning: use Temporal's patched/versioning APIs; never change a running workflow's deterministic path without a patch.
- Feature flags for consumer-mode changes and replay rate limits.

---

## 12. Testing strategy

| Layer | Tooling | What it proves |
|---|---|---|
| Unit | JUnit 5, AssertJ, jqwik | State transitions, canonical hashing, backoff, validation |
| Repository | Testcontainers PostgreSQL | `SKIP LOCKED`, `ON CONFLICT`, fences, RLS, projection monotonicity |
| Broker | Azure Service Bus emulator / test namespace | Peek-lock, complete/abandon, DLQ, filters, sessions |
| HTTP | WireMock | Webhook signatures, timeouts, retry classification, SSRF rejection |
| Workflow | Temporal test environment | Approval gates, retries, timers, activation idempotency |
| Contract | springdoc + JSON Schema + Spring Cloud Contract | Public API and webhook payload compatibility |
| Architecture | ArchUnit / Spring Modulith | Module boundaries, no domain-to-web dependency |
| Concurrency | Custom harness | Duplicate publish, activation vs publish, lease expiry, outbox crash |
| Load | Gatling/k6 | Publish throughput, poll contention, outbox drain, fan-out |
| Chaos | Toxiproxy/Testcontainers | DB failover, broker throttling, webhook 5xx, clock skew |

Must-have regression scenarios:

1. Two concurrent publishes with the same ID and same content → one message, both get `202`.
2. Two concurrent publishes with the same ID and different content → one `202`, one `409`.
3. Worker crashes after broker ACK before outbox update → duplicate send; consumer dedupes.
4. Activation starts while a publish transaction holds the shared gate → activation waits, then flips; no message is accepted against a suspended flow.
5. Poll lease expires during processing → redelivery with a new fence; old token gets `409`.
6. Webhook times out after the consumer processed → retry; consumer dedupes by delivery ID.
7. Receipt `SUCCEEDED` followed by a late `FAILED` → history keeps both, projection stays `SUCCEEDED`.
8. Replay to subscriber A does not wake subscriber B.
9. Replay after the 30-day retention window → `410`, no partial send.
10. Plan digest changes after approval → apply is refused; a new preview is required.

---

## 13. Delivery roadmap

| Phase | Outcome |
|---|---|
| 0. Foundation | Multi-module build, Flyway, Testcontainers, JWT security, OTel, CI, local compose (Postgres + Service Bus emulator) |
| 1. Publish path | `POST /messages`, gate, idempotency, schema validation, outbox, dispatcher, direct consumer happy path |
| 2. Bridge delivery | Broker receiver/stager, webhook sender, polling lease/renew/settle, quarantine, retry budgets |
| 3. Receipts | Receipt ingest, immutable history, projection, status APIs, consumer contract |
| 4. Recovery | DLQ observer, replay operation, replay topic/filter, generation handling, operator audit |
| 5. Control plane | Flow/revision CRUD, validation, approvals, ETags, audit, runtime catalog |
| 6. Lifecycle | Temporal workflow, plan-only/apply-only jobs, verification, activation, suspend/drain/retire |
| 7. Hardening | Load, chaos, DR drill, runbooks, dashboards, penetration test, cost review |

Do not start with the control plane. The publish/delivery/receipt path is the product; the control plane can initially be a seeded catalog plus manual SQL for internal pilots, then replace it with the workflow.

---

## 14. Risks and open decisions

| Decision | Recommendation | Trade-off |
|---|---|---|
| Workflow engine | Temporal | Extra operational component; best code-first durability and testing. Durable Functions is the Azure-native fallback; Camunda if BPMN is required |
| Gateway | Keep APIM | Do not rebuild quotas/token validation in Spring Cloud Gateway |
| Hot-path ORM | jOOQ | More SQL, less magic; necessary for fences and `SKIP LOCKED` |
| Tenancy | `tenant_id` + RLS | RLS adds policy maintenance; schema-per-tenant is heavier |
| Ordering | Opt-in Service Bus sessions | Sessions reduce parallelism and complicate replay |
| Payload >128 KiB | Claim-check via Blob + reference | More moving parts; defer until a real consumer needs it |
| Exactly-once | Not attempted | At-least-once + idempotent consumers is the only honest contract |
| Multi-region | Single region in v1; geo-restore | Active-active requires conflict-free IDs and cross-region ordering decisions |
| Consumer mode change | New revision + activation | Avoid in-place mode flips that strand in-flight deliveries |
| Receipt sequence | Consumer-supplied, server-validated | Server-assigned is simpler but loses consumer ordering semantics |
| Retention | 30 days for replay; longer for receipts | Storage cost vs recovery/audit needs |
| Self-hosted Temporal | Prefer Temporal Cloud for v1 | Self-hosting adds Cassandra/Postgres + visibility store operations |

---

## Appendix A — Key dependencies

```gradle
// Web + security
implementation "org.springframework.boot:spring-boot-starter-web"
implementation "org.springframework.boot:spring-boot-starter-validation"
implementation "org.springframework.boot:spring-boot-starter-security"
implementation "org.springframework.boot:spring-boot-starter-oauth2-resource-server"
implementation "org.springframework.boot:spring-boot-starter-actuator"

// Persistence
implementation "org.springframework.boot:spring-boot-starter-jooq"
implementation "org.flywaydb:flyway-core"
implementation "org.flywaydb:flyway-database-postgresql"
runtimeOnly "org.postgresql:postgresql"

// Azure
implementation "com.azure:azure-messaging-servicebus"
implementation "com.azure:azure-identity"
implementation "com.azure:azure-identity-extensions"      // PostgreSQL Entra auth
implementation "com.azure.spring:spring-cloud-azure-starter-keyvault-secrets"

// Workflow
implementation "io.temporal:temporal-sdk"

// Resilience + docs + observability
implementation "io.github.resilience4j:resilience4j-spring-boot3"
implementation "org.springdoc:springdoc-openapi-starter-webmvc-ui"
implementation "io.micrometer:micrometer-tracing-bridge-otel"
implementation "io.opentelemetry:opentelemetry-exporter-otlp"

// Test
testImplementation "org.springframework.boot:spring-boot-starter-test"
testImplementation "org.testcontainers:postgresql"
testImplementation "org.wiremock:wiremock-standalone"
testImplementation "com.tngtech.archunit:archunit-junit5"
testImplementation "io.temporal:temporal-testing"
```

Align versions with the chosen Spring Boot minor; avoid pinning transitive Azure SDK versions manually.

## Appendix B — Transport naming

Logical IDs must never be used directly as broker names. Keep a catalog mapping:

| Logical | Transport |
|---|---|
| `flow_id = orders` | `sb-prod-tenant42-orders` |
| `subscriber_id = billing` | `sub-billing` |
| replay | topic `sb-prod-tenant42-orders-replay`, subscription `sub-billing-replay`, filter `targetSubscriberId = 'billing'` |

Service Bus has name-length limits and reserved characters. Sanitize and hash long IDs; store the mapping in the immutable bundle so it is stable across activations.

## Appendix C — Consumer contracts (summary)

**Direct broker consumer**
- Receive with peek-lock; process; commit business effects + inbox row; complete the broker message.
- Dedupe on `MessageId`; dead-letter permanent failures with a reason.
- Use sessions only if the flow's ordering policy requires them.

**Webhook consumer**
- Verify signature and timestamp; dedupe on `X-Exchange-Delivery`.
- Return `2xx` only after durable custody of the event (inbox row).
- Treat `4xx` (except `408`/`429`) as permanent; the platform will quarantine.

**Polling consumer**
- `POST /deliveries/lease`; process; commit business effects + receipt intent; `POST /settle`.
- Renew if processing exceeds the lease; handle `409 LEASE_LOST` by discarding local assumptions.
- Use a local receipt outbox and retry `POST /v1/receipts` with the same `reportId`.

**Receipt reporter**
- Send one immutable outcome per logical attempt; `SUCCEEDED` is terminal.
- Include a monotonic `sequence`; late reports are retained but never downgrade success.
