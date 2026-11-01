-- Exchange data plane schema.
-- One row per tenant/flow is the authoritative admission gate.
create table flow_gate (
    tenant_id        text        not null,
    flow_id          text        not null,
    state            text        not null check (state in ('ACTIVE', 'SUSPENDED', 'DRAINING', 'RETIRED')),
    active_revision  bigint,
    generation       bigint      not null default 0,
    catalog_checksum text        not null,
    updated_at       timestamptz not null default now(),
    primary key (tenant_id, flow_id)
);

-- Immutable accepted message. Retention is enforced by expires_at.
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

-- Expected/actual delivery ledger: one row per message x subscriber x generation.
create table message_subscriber (
    tenant_id        text        not null,
    flow_id          text        not null,
    message_id       text        not null,
    subscriber_id    text        not null,
    generation       bigint      not null default 0,
    binding_revision bigint      not null,
    delivery_mode    text        not null check (delivery_mode in ('DIRECT', 'WEBHOOK', 'PULL')),
    dispatch_state   text        not null check (dispatch_state in ('EXPECTED', 'STAGED', 'DELIVERED', 'QUARANTINED', 'BROKER_OWNED')),
    updated_at       timestamptz not null default now(),
    primary key (tenant_id, flow_id, message_id, subscriber_id, generation)
);
create index message_subscriber_sub_idx
    on message_subscriber (tenant_id, subscriber_id, dispatch_state, updated_at);

-- Transactional outbox: one row per logical message. Broker fan-out handles subscribers.
create table publish_outbox (
    id               bigint      generated always as identity primary key,
    tenant_id        text        not null,
    flow_id          text        not null,
    message_id       text        not null,
    state            text        not null check (state in ('PENDING', 'CLAIMED', 'PUBLISHED', 'BLOCKED')),
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
create index publish_outbox_lease_idx
    on publish_outbox (lease_expires_at)
    where state = 'CLAIMED';

-- Bridge work item for webhook/poll subscribers only.
create table delivery (
    id               uuid        primary key,
    tenant_id        text        not null,
    flow_id          text        not null,
    message_id       text        not null,
    subscriber_id    text        not null,
    generation       bigint      not null,
    mode             text        not null check (mode in ('WEBHOOK', 'PULL')),
    state            text        not null check (state in ('STAGED', 'LEASED', 'DELIVERED', 'QUARANTINED')),
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
    where state in ('STAGED', 'LEASED');
create index delivery_sub_idx
    on delivery (tenant_id, subscriber_id, state, next_attempt_at);

create table delivery_attempt (
    delivery_id uuid        not null references delivery (id) on delete cascade,
    attempt     int         not null,
    started_at  timestamptz not null,
    finished_at timestamptz,
    outcome     text        not null check (outcome in ('SUCCESS', 'RETRYABLE', 'PERMANENT', 'TIMEOUT')),
    http_status int,
    error_code  text,
    primary key (delivery_id, attempt)
);

-- Immutable business-outcome history. Source of truth for processing state.
create table receipt_history (
    id            bigint      generated always as identity primary key,
    tenant_id     text        not null,
    subscriber_id text        not null,
    report_id     text        not null,
    message_id    text        not null,
    generation    bigint      not null,
    sequence      bigint      not null,
    outcome       text        not null check (outcome in ('PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
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
    status         text        not null check (status in ('NOT_REPORTED', 'PROCESSING', 'SUCCEEDED', 'UNKNOWN_FAILED')),
    last_sequence  bigint      not null,
    last_report_id text        not null,
    updated_at     timestamptz not null default now(),
    primary key (tenant_id, subscriber_id, message_id, generation)
);

-- One operator-initiated, target-specific replay.
create table replay_operation (
    id               uuid        primary key,
    tenant_id        text        not null,
    flow_id          text        not null,
    subscriber_id    text        not null,
    requested_by     text        not null,
    reason           text        not null,
    selector         jsonb       not null,
    state            text        not null check (state in ('REQUESTED', 'CLAIMED', 'REPLAYING', 'COMPLETED', 'FAILED')),
    generation       bigint      not null,
    attempt          int         not null default 0,
    lease_owner      text,
    lease_expires_at timestamptz,
    fence            bigint      not null default 0,
    created_at       timestamptz not null default now(),
    completed_at     timestamptz
);

-- Read model published by the control plane on activation.
create table catalog_subscriber (
    tenant_id        text        not null,
    flow_id          text        not null,
    revision         bigint      not null,
    subscriber_id    text        not null,
    delivery_mode    text        not null check (delivery_mode in ('DIRECT', 'WEBHOOK', 'PULL')),
    required         boolean     not null default true,
    binding_revision bigint      not null,
    primary key (tenant_id, flow_id, revision, subscriber_id)
);

create table catalog_schema (
    tenant_id   text   not null,
    flow_id     text   not null,
    revision    bigint not null,
    schema_json jsonb  not null,
    primary key (tenant_id, flow_id, revision)
);
