-- WP4: strategies, immutable hash-addressed versions, validations and import records.

create table strategies (
  id                 uuid primary key,
  name               text not null,
  asset_class        text not null check (asset_class in ('US_EQUITY', 'CRYPTO')),
  status             text not null check (status in ('DRAFT', 'VALIDATION_FAILED', 'MANUAL_REVIEW_REQUIRED', 'VALIDATED', 'BACKTESTED',
                       'PAPER_ELIGIBLE', 'ACTIVE_RECOMMENDATION', 'ACTIVE_AUTONOMOUS', 'PAUSED', 'SUSPENDED', 'ARCHIVED')),
  status_reason      text,
  current_version_id uuid,
  cloned_from        uuid references strategies(id),
  created_at         timestamptz not null,
  updated_at         timestamptz not null,
  archived_at        timestamptz,
  version            bigint not null default 0
);

create table strategy_versions (
  id                uuid primary key,
  strategy_id       uuid not null references strategies(id),
  version_number    int not null,
  schema_version    text not null,
  content           jsonb not null,
  canonical_content text not null,
  content_hash      text not null,
  source            text not null check (source in ('OWNER', 'IMPORT', 'AI_COMPILED', 'CLONE')),
  source_ref        text,
  validation_status text not null check (validation_status in ('PENDING', 'VALIDATED', 'VALIDATION_FAILED', 'MANUAL_REVIEW_REQUIRED')),
  created_at        timestamptz not null,
  unique (strategy_id, version_number)
);
create index strategy_versions_hash_idx on strategy_versions (content_hash);
alter table strategies add constraint strategies_current_version_fk foreign key (current_version_id) references strategy_versions(id);

-- Versions are immutable once created (FR-044); only the validation status may advance.
create or replace function sf_strategy_version_immutable() returns trigger
language plpgsql as $$
begin
  if TG_OP = 'DELETE' then
    raise exception 'strategy versions are immutable' using errcode = '55000';
  end if;
  if new.content is distinct from old.content or new.canonical_content is distinct from old.canonical_content
     or new.content_hash is distinct from old.content_hash or new.strategy_id is distinct from old.strategy_id
     or new.version_number is distinct from old.version_number or new.source is distinct from old.source then
    raise exception 'strategy version % content is immutable', old.id using errcode = '55000';
  end if;
  return new;
end;
$$;
create trigger strategy_versions_immutable before update or delete on strategy_versions for each row execute function sf_strategy_version_immutable();

create table strategy_validations (
  id               uuid primary key,
  version_id       uuid not null references strategy_versions(id),
  status           text not null check (status in ('VALIDATED', 'VALIDATION_FAILED', 'MANUAL_REVIEW_REQUIRED')),
  issues           jsonb not null,
  unknown_fields   jsonb not null,
  provider         text,
  validator_version text not null,
  validated_at     timestamptz not null
);
create index strategy_validations_version_idx on strategy_validations (version_id, validated_at desc);
create trigger strategy_validations_append_only before update or delete on strategy_validations for each row execute function sf_prevent_mutation();

create table strategy_imports (
  id          uuid primary key,
  received_at timestamptz not null,
  size_bytes  int not null,
  sha256      text not null,
  filename    text,
  outcome     text not null check (outcome in ('VALIDATED', 'VALIDATION_FAILED', 'MANUAL_REVIEW_REQUIRED', 'REJECTED')),
  strategy_id uuid references strategies(id),
  version_id  uuid references strategy_versions(id),
  issues      jsonb not null,
  -- Original bytes are retained as inert text for provenance; they are never executed.
  original    text
);
create trigger strategy_imports_append_only before update or delete on strategy_imports for each row execute function sf_prevent_mutation();

create table strategy_status_history (
  id          bigserial primary key,
  strategy_id uuid not null references strategies(id),
  from_status text,
  to_status   text not null,
  reason      text,
  at          timestamptz not null
);
create trigger strategy_status_history_append_only before update or delete on strategy_status_history for each row execute function sf_prevent_mutation();
