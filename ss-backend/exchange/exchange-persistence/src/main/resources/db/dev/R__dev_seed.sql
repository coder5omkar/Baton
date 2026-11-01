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
on conflict (tenant_id, flow_id, revision, subscriber_id) do update
set delivery_mode = excluded.delivery_mode,
    required = excluded.required,
    binding_revision = excluded.binding_revision;

insert into catalog_schema (tenant_id, flow_id, revision, schema_json)
values ('tenant-dev', 'orders', 1, $schema$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["specversion", "id", "source", "type", "data"],
  "properties": {
    "specversion": {"const": "1.0"},
    "data": {
      "type": "object",
      "required": ["orderId"],
      "properties": {
        "orderId": {"type": "string"},
        "amount": {"type": "number"}
      }
    }
  }
}
$schema$::jsonb)
on conflict (tenant_id, flow_id, revision) do update
set schema_json = excluded.schema_json;
