-- WP8: AI research sessions, runs, sources, edits, compilations and budgets (FR-030..FR-037, FR-092).
-- AI output is untrusted: it is stored verbatim for provenance, labelled UNVERIFIED until the owner
-- reviews it, and can reach the strategy library only through the regular strategy validator.

-- Global ceilings (FR-037). Loosening requires recent authentication (enforced in the service).
create table ai_budget (
  singleton                boolean primary key default true check (singleton),
  monthly_cost_limit_usd   numeric(38,12) not null check (monthly_cost_limit_usd >= 0),
  daily_request_limit      int not null check (daily_request_limit >= 0),
  max_output_tokens        int not null check (max_output_tokens between 64 and 64000),
  max_input_chars          int not null check (max_input_chars between 1000 and 400000),
  updated_at               timestamptz not null,
  version                  bigint not null default 0
);
insert into ai_budget(singleton, monthly_cost_limit_usd, daily_request_limit, max_output_tokens, max_input_chars, updated_at)
values (true, 20, 40, 8000, 60000, now());

create table research_sessions (
  id                 uuid primary key,
  title              text not null,
  provider_id        uuid not null references provider_configurations(id),
  provider_type      text not null,
  model              text not null,
  asset_class        text not null check (asset_class in ('US_EQUITY', 'CRYPTO')),
  universe           jsonb not null,
  horizon            text not null,
  timeframe          text not null,
  approach           text not null,
  prompt             text not null,
  retrieval          boolean not null,
  max_requests       int not null check (max_requests between 1 and 20),
  max_cost_usd       numeric(38,12) not null check (max_cost_usd > 0),
  status             text not null check (status in ('DRAFT', 'RUNNING', 'COMPLETED', 'FAILED')),
  review_status      text not null check (review_status in ('UNVERIFIED', 'REVIEWED', 'REJECTED')),
  reviewed_at        timestamptz,
  review_note        text,
  created_at         timestamptz not null,
  updated_at         timestamptz not null,
  version            bigint not null default 0
);

-- One row per provider call (research or compilation). The prompt, parameters, raw response,
-- usage and estimated cost are immutable once the run completes (FR-032).
create table research_runs (
  id                  uuid primary key,
  session_id          uuid not null references research_sessions(id),
  purpose             text not null check (purpose in ('RESEARCH', 'COMPILE')),
  provider_id         uuid not null references provider_configurations(id),
  provider_type       text not null,
  model               text not null,
  prompt_version      text not null,
  system_prompt       text not null,
  user_prompt         text not null,
  parameters          jsonb not null,
  status              text not null check (status in ('RUNNING', 'SUCCEEDED', 'FAILED')),
  failure_code        text,
  failure_detail      text,
  response_text       text,
  response_raw        text,
  stop_reason         text,
  input_tokens        bigint,
  output_tokens       bigint,
  search_requests     bigint,
  reserved_cost_usd   numeric(38,12) not null,
  estimated_cost_usd  numeric(38,12),
  sources_required    boolean not null,
  started_at          timestamptz not null,
  completed_at        timestamptz
);
create index research_runs_session_idx on research_runs (session_id, started_at);
create index research_runs_started_idx on research_runs (started_at);
create or replace function sf_research_run_immutable() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    if current_setting('strategyforge.allow_purge', true) = 'on' then return old; end if;
    raise exception 'research runs are append-only';
  end if;
  if old.status <> 'RUNNING' then
    raise exception 'completed research runs are immutable';
  end if;
  if new.system_prompt is distinct from old.system_prompt or new.user_prompt is distinct from old.user_prompt
     or new.parameters is distinct from old.parameters or new.model is distinct from old.model then
    raise exception 'research run request fields are immutable';
  end if;
  return new;
end $$;
create trigger research_runs_immutable before update or delete on research_runs for each row execute function sf_research_run_immutable();

create table research_sources (
  id           uuid primary key,
  run_id       uuid not null references research_runs(id),
  position     int not null,
  url          text not null,
  title        text,
  cited_text   text,
  page_age     text,
  created_at   timestamptz not null,
  unique (run_id, position)
);
create trigger research_sources_append_only before update or delete on research_sources for each row execute function sf_prevent_mutation();

-- Owner review edits (FR-032 "edits"): each edit is a full replacement of the research text.
create table research_edits (
  id           uuid primary key,
  session_id   uuid not null references research_sessions(id),
  base_run_id  uuid not null references research_runs(id),
  content      text not null,
  note         text,
  created_at   timestamptz not null,
  actor        text not null
);
create trigger research_edits_append_only before update or delete on research_edits for each row execute function sf_prevent_mutation();

-- Controlled compilation (FR-035, FR-036): candidate JSON, validation outcome and resulting version/hash.
create table strategy_compilations (
  id                 uuid primary key,
  session_id         uuid not null references research_sessions(id),
  run_id             uuid references research_runs(id),
  source_edit_id     uuid references research_edits(id),
  source_run_id      uuid not null references research_runs(id),
  compiler_version   text not null,
  status             text not null check (status in ('COMPILED', 'MANUAL_REVIEW_REQUIRED', 'VALIDATION_FAILED', 'REJECTED', 'FAILED')),
  candidate          text,
  issues             jsonb not null default '[]'::jsonb,
  strategy_id        uuid references strategies(id),
  version_id         uuid references strategy_versions(id),
  content_hash       text,
  created_at         timestamptz not null
);
create index strategy_compilations_version_idx on strategy_compilations (version_id);
create trigger strategy_compilations_append_only before update or delete on strategy_compilations for each row execute function sf_prevent_mutation();
