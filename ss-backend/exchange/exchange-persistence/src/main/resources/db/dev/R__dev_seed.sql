-- Local-development seed. This location is only added by the "local" profile.
insert into flow_gate (tenant_id, flow_id, state, active_revision, generation, catalog_checksum)
values ('tenant-dev', 'orders', 'ACTIVE', 1, 1, 'dev-checksum')
on conflict (tenant_id, flow_id) do update
set state = excluded.state,
    active_revision = excluded.active_revision,
    generation = excluded.generation,
    catalog_checksum = excluded.catalog_checksum,
    updated_at = now();

insert into catalog_subscriber
    (tenant_id, flow_id, revision, subscriber_id, delivery_mode, required, binding_revision)
values
    ('tenant-dev', 'orders', 1, 'billing', 'WEBHOOK', true, 1),
    ('tenant-dev', 'orders', 1, 'analytics', 'PULL', true, 1)
on conflict (tenant_id, flow_id, revision, subscriber_id) do nothing;

insert into catalog_schema (tenant_id, flow_id, revision, schema_json)
values ('tenant-dev', 'orders', 1, '{"type":"object"}'::jsonb)
on conflict (tenant_id, flow_id, revision) do nothing;
