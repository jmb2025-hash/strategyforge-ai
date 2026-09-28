-- WP6: risk profiles, activations, scheduler evaluation locks, signals, recommendations, emergency state.

create table risk_profiles (
  id         uuid primary key,
  scope      text not null check (scope in ('GLOBAL', 'PORTFOLIO', 'STRATEGY')),
  scope_id   uuid,
  limits     jsonb not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  version    bigint not null default 0,
  constraint scope_id_required check ((scope = 'GLOBAL') = (scope_id is null))
);
create unique index risk_profiles_scope_idx on risk_profiles (scope, coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Conservative global defaults (FR-003 risk defaults). Owner may tighten freely; loosening needs recent authentication.
insert into risk_profiles(id, scope, scope_id, limits, created_at, updated_at) values (
  '00000000-0000-0000-0000-00000000a001', 'GLOBAL', null,
  '{"maxTradePercentOfEquity":"20","maxInstrumentAllocationPercent":"25","maxAssetClassAllocationPercent":{"US_EQUITY":"100","CRYPTO":"50"},
    "maxOpenPositions":25,"maxTradesPerMinute":5,"maxTradesPerHour":60,"maxTradesPerDay":200,"cooldownSeconds":0,
    "maxDailyLossPercent":"5","maxDrawdownPercent":"25","maxConsecutiveLosses":6,"maxShortExposurePercent":"50","shortingAllowed":true,
    "maxQuoteAgeSeconds":60,"maxSpreadPercent":"1.0","maxParticipationPercent":"10","maxPriceDeviationPercent":"15",
    "maxConsecutiveErrors":3,"denySymbols":[]}'::jsonb,
  now(), now());

-- Emergency controls (section 4). Singleton row.
create table emergency_state (
  singleton             boolean primary key default true check (singleton),
  pause_all             boolean not null default false,
  prevent_new_positions boolean not null default false,
  reason                text,
  updated_at            timestamptz not null,
  updated_by            text,
  version               bigint not null default 0
);
insert into emergency_state(singleton, updated_at) values (true, now());

create table strategy_activations (
  id                 uuid primary key,
  strategy_id        uuid not null references strategies(id),
  version_id         uuid not null references strategy_versions(id),
  content_hash       text not null,
  portfolio_id       uuid not null references portfolios(id),
  mode               text not null check (mode in ('RECOMMENDATION', 'AUTONOMOUS')),
  allocation_percent numeric(38,12) not null check (allocation_percent > 0 and allocation_percent <= 100),
  backtest_id        uuid not null references backtests(id),
  disclosure_version text,
  authorized_at      timestamptz,
  authorized_session uuid,
  fingerprint        text,
  status             text not null check (status in ('ACTIVE', 'ENDED')),
  created_at         timestamptz not null,
  ended_at           timestamptz,
  end_reason         text
);
create unique index strategy_activations_one_active on strategy_activations (strategy_id) where status = 'ACTIVE';

-- Scheduler lock by strategy and evaluation time bucket (section 8): a bucket is evaluated at most once.
create table strategy_evaluations (
  id            uuid primary key,
  strategy_id   uuid not null references strategies(id),
  activation_id uuid not null references strategy_activations(id),
  bucket_start  timestamptz not null,
  status        text not null check (status in ('CLAIMED', 'COMPLETED', 'BLOCKED', 'FAILED')),
  detail        jsonb not null default '{}'::jsonb,
  started_at    timestamptz not null,
  completed_at  timestamptz,
  unique (strategy_id, bucket_start)
);

create table signals (
  id                 uuid primary key,
  strategy_id        uuid not null references strategies(id),
  version_id         uuid not null references strategy_versions(id),
  content_hash       text not null,
  activation_id      uuid not null references strategy_activations(id),
  evaluation_id      uuid not null references strategy_evaluations(id),
  portfolio_id       uuid not null references portfolios(id),
  instrument_id      uuid not null references instruments(id),
  bucket_start       timestamptz not null,
  action             text not null check (action in ('ENTER_LONG', 'EXIT_LONG', 'ENTER_SHORT', 'EXIT_SHORT')),
  side               text not null,
  quantity           numeric(38,18) not null check (quantity > 0),
  order_type         text not null,
  limit_price        numeric(38,12),
  reference_price    numeric(38,12) not null,
  market_snapshot_id uuid not null references market_snapshots(id),
  triggered_rules    jsonb not null,
  rationale          text not null,
  expires_at         timestamptz not null,
  disposition        text not null check (disposition in ('RECOMMENDED', 'AUTO_EXECUTED', 'BLOCKED')),
  risk_evaluation_id uuid references risk_evaluations(id),
  created_at         timestamptz not null,
  unique (strategy_id, instrument_id, bucket_start, action)
);
create trigger signals_append_only before delete on signals for each row execute function sf_prevent_mutation();

create table recommendations (
  id                   uuid primary key,
  signal_id            uuid not null unique references signals(id),
  strategy_id          uuid not null references strategies(id),
  portfolio_id         uuid not null references portfolios(id),
  instrument_id        uuid not null references instruments(id),
  status               text not null check (status in ('CREATED', 'BLOCKED', 'PENDING', 'ACCEPTED', 'MODIFIED', 'DECLINED', 'EXPIRED', 'SUPERSEDED', 'FAILED')),
  status_reason        text,
  side                 text not null,
  quantity             numeric(38,18) not null,
  order_type           text not null,
  limit_price          numeric(38,12),
  reference_price      numeric(38,12) not null,
  max_deviation_percent numeric(38,12) not null,
  expires_at           timestamptz not null,
  snoozed_until        timestamptz,
  risk_evaluation_id   uuid references risk_evaluations(id),
  order_id             uuid unique references paper_orders(id),
  created_at           timestamptz not null,
  updated_at           timestamptz not null,
  decided_at           timestamptz,
  version              bigint not null default 0
);
create index recommendations_status_idx on recommendations (status, expires_at);

create table recommendation_decisions (
  id                uuid primary key,
  recommendation_id uuid not null references recommendations(id),
  decision          text not null check (decision in ('CREATE', 'BLOCK', 'ACCEPT', 'MODIFY', 'DECLINE', 'SNOOZE', 'PAUSE_STRATEGY', 'EXPIRE', 'SUPERSEDE', 'FAIL')),
  actor             text not null,
  details           jsonb not null default '{}'::jsonb,
  at                timestamptz not null,
  market_time       timestamptz not null
);
create index recommendation_decisions_idx on recommendation_decisions (recommendation_id, at);
create trigger recommendation_decisions_append_only before update or delete on recommendation_decisions for each row execute function sf_prevent_mutation();
