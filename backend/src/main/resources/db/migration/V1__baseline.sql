-- StrategyForge V1 baseline: audit trail, idempotency, append-only enforcement.
-- All timestamps are timestamptz stored in UTC (NFR-001).

create extension if not exists pgcrypto;

-- Generic guard used by every append-only table. Purges are only possible inside an
-- explicit export-and-purge operation that sets strategyforge.allow_purge = 'on'.
create or replace function sf_prevent_mutation() returns trigger
language plpgsql as $$
begin
  if current_setting('strategyforge.allow_purge', true) = 'on' and TG_OP in ('DELETE', 'TRUNCATE') then
    if TG_OP = 'DELETE' then return old; end if;
    return null;
  end if;
  raise exception 'append-only table % does not permit %', TG_TABLE_NAME, TG_OP using errcode = '55000';
end;
$$;

create table audit_events (
  id             bigserial primary key,
  event_id       uuid not null unique,
  occurred_at    timestamptz not null,
  actor          text not null,
  category       text not null,
  action         text not null,
  outcome        text not null check (outcome in ('SUCCESS', 'FAILURE', 'BLOCKED', 'DENIED')),
  entity_type    text,
  entity_id      text,
  correlation_id text,
  details        jsonb not null default '{}'::jsonb,
  prev_hash      text,
  hash           text not null
);
create index audit_events_category_idx on audit_events (category, id desc);
create index audit_events_entity_idx on audit_events (entity_type, entity_id);

create trigger audit_events_append_only before update or delete on audit_events
  for each row execute function sf_prevent_mutation();
create trigger audit_events_no_truncate before truncate on audit_events
  for each statement execute function sf_prevent_mutation();

create table idempotency_records (
  scope           text not null,
  idem_key        text not null,
  request_hash    text not null,
  status          text not null check (status in ('IN_PROGRESS', 'COMPLETED')),
  response_status int,
  response_body   text,
  created_at      timestamptz not null,
  completed_at    timestamptz,
  primary key (scope, idem_key)
);

-- System health events (FR-111) are appended by operations components.
create table system_health_events (
  id          bigserial primary key,
  occurred_at timestamptz not null,
  component   text not null,
  status      text not null check (status in ('OK', 'DEGRADED', 'FAILED', 'UNKNOWN')),
  detail      text,
  data        jsonb not null default '{}'::jsonb
);
create index system_health_events_component_idx on system_health_events (component, occurred_at desc);
