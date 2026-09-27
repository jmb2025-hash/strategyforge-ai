-- WP3: paper portfolios, append-only double-entry ledger, lots, paper orders and executions.
-- Real-money boundary: every portfolio is PAPER and every order executes in PAPER_SIMULATOR (FR-113).

create table portfolios (
  id                     uuid primary key,
  name                   text not null,
  account_type           text not null default 'PAPER' check (account_type = 'PAPER'),
  status                 text not null check (status in ('ACTIVE', 'ARCHIVED')),
  base_currency          text not null default 'USD' check (base_currency = 'USD'),
  starting_balance       numeric(38,12) not null check (starting_balance > 0),
  cost_model             jsonb not null,
  shorting_enabled       boolean not null default false,
  cloned_from            uuid references portfolios(id),
  reset_from             uuid references portfolios(id),
  reconciliation_status  text not null default 'OK' check (reconciliation_status in ('OK', 'FAILED', 'UNVERIFIED')),
  created_at             timestamptz not null,
  archived_at            timestamptz,
  version                bigint not null default 0
);
create unique index portfolios_active_name on portfolios (lower(name)) where status = 'ACTIVE';

create table ledger_journals (
  id             uuid primary key,
  seq            bigserial not null unique,
  portfolio_id   uuid not null references portfolios(id),
  journal_type   text not null check (journal_type in ('FUNDING', 'RESERVATION', 'RESERVATION_RELEASE', 'EXECUTION', 'FEE', 'BORROW_FEE',
                   'DIVIDEND', 'SPLIT', 'ADJUSTMENT')),
  reference_type text,
  reference_id   text,
  occurred_at    timestamptz not null,
  recorded_at    timestamptz not null,
  description    text not null,
  correlation_id text
);
create index ledger_journals_portfolio_idx on ledger_journals (portfolio_id, seq);

create table ledger_entries (
  id            bigserial primary key,
  journal_id    uuid not null references ledger_journals(id),
  portfolio_id  uuid not null references portfolios(id),
  account       text not null check (account in ('CASH', 'CASH_RESERVED', 'POSITION', 'OWNER_CAPITAL', 'REALIZED_PNL', 'FEES',
                  'BORROW_FEES', 'DIVIDENDS')),
  instrument_id uuid references instruments(id),
  amount        numeric(38,12) not null,
  quantity      numeric(38,18) not null default 0,
  constraint position_needs_instrument check ((account = 'POSITION') = (instrument_id is not null)),
  constraint quantity_only_on_positions check (account = 'POSITION' or quantity = 0)
);
create index ledger_entries_portfolio_idx on ledger_entries (portfolio_id, account, instrument_id);
create index ledger_entries_journal_idx on ledger_entries (journal_id);

create trigger ledger_journals_append_only before update or delete on ledger_journals for each row execute function sf_prevent_mutation();
create trigger ledger_journals_no_truncate before truncate on ledger_journals for each statement execute function sf_prevent_mutation();
create trigger ledger_entries_append_only before update or delete on ledger_entries for each row execute function sf_prevent_mutation();
create trigger ledger_entries_no_truncate before truncate on ledger_entries for each statement execute function sf_prevent_mutation();

-- Double-entry invariant enforced by the database at commit: every journal sums to zero (FR-011).
create or replace function sf_check_journal_balanced() returns trigger
language plpgsql as $$
declare
  total numeric;
  n int;
begin
  select coalesce(sum(amount), 0), count(*) into total, n from ledger_entries where journal_id = new.journal_id;
  if total <> 0 then
    raise exception 'ledger journal % is unbalanced (sum %)', new.journal_id, total using errcode = '23514';
  end if;
  return null;
end;
$$;
create constraint trigger ledger_entries_balanced after insert on ledger_entries
  deferrable initially deferred for each row execute function sf_check_journal_balanced();

create table position_lots (
  id                 uuid primary key,
  portfolio_id       uuid not null references portfolios(id),
  instrument_id      uuid not null references instruments(id),
  side               text not null check (side in ('LONG', 'SHORT')),
  opened_at          timestamptz not null,
  open_execution_id  uuid,
  quantity_open      numeric(38,18) not null check (quantity_open > 0),
  quantity_remaining numeric(38,18) not null check (quantity_remaining >= 0),
  -- Signed remaining cost basis: positive for longs, negative for shorts (matches the POSITION ledger account).
  cost_remaining     numeric(38,12) not null,
  closed_at          timestamptz
);
create index position_lots_open_idx on position_lots (portfolio_id, instrument_id, opened_at) where quantity_remaining > 0;

create table paper_orders (
  id                   uuid primary key,
  portfolio_id         uuid not null references portfolios(id),
  instrument_id        uuid not null references instruments(id),
  side                 text not null check (side in ('BUY', 'SELL', 'SELL_SHORT', 'BUY_TO_COVER')),
  order_type           text not null check (order_type in ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT')),
  time_in_force        text not null check (time_in_force in ('DAY', 'GTC')),
  quantity             numeric(38,18) not null check (quantity > 0),
  limit_price          numeric(38,12) check (limit_price > 0),
  stop_price           numeric(38,12) check (stop_price > 0),
  status               text not null check (status in ('CREATED', 'VALIDATED', 'REJECTED', 'PENDING', 'PARTIALLY_FILLED', 'FILLED',
                         'CANCELLED', 'EXPIRED', 'FAILED')),
  source               text not null check (source in ('MANUAL', 'RECOMMENDATION', 'AUTONOMOUS', 'EMERGENCY_CLOSE', 'FORCED_COVER', 'SYSTEM')),
  venue                text not null default 'PAPER_SIMULATOR' check (venue = 'PAPER_SIMULATOR'),
  strategy_id          uuid,
  strategy_version_id  uuid,
  recommendation_id    uuid unique,
  signal_id            uuid,
  market_snapshot_id   uuid references market_snapshots(id),
  risk_evaluation_id   uuid,
  filled_quantity      numeric(38,18) not null default 0,
  average_fill_price   numeric(38,12),
  reserved_amount      numeric(38,12) not null default 0,
  reservation_released numeric(38,12) not null default 0,
  triggered            boolean not null default false,
  last_fill_quote_ts   timestamptz,
  rejection_reason     text,
  created_at           timestamptz not null,
  eligible_at          timestamptz not null,
  expires_at           timestamptz not null,
  updated_at           timestamptz not null,
  version              bigint not null default 0,
  constraint limit_price_required check (order_type not in ('LIMIT', 'STOP_LIMIT') or limit_price is not null),
  constraint stop_price_required check (order_type not in ('STOP', 'STOP_LIMIT') or stop_price is not null),
  constraint filled_within_quantity check (filled_quantity >= 0 and filled_quantity <= quantity),
  constraint strategy_orders_linked check (source not in ('RECOMMENDATION', 'AUTONOMOUS') or strategy_version_id is not null)
);
create index paper_orders_open_idx on paper_orders (status, eligible_at) where status in ('PENDING', 'PARTIALLY_FILLED');
create index paper_orders_portfolio_idx on paper_orders (portfolio_id, created_at desc);

create table order_status_history (
  id          bigserial primary key,
  order_id    uuid not null references paper_orders(id),
  from_status text,
  to_status   text not null,
  at          timestamptz not null,
  market_time timestamptz not null,
  reason      text
);
create index order_status_history_order_idx on order_status_history (order_id, id);
create trigger order_status_history_append_only before update or delete on order_status_history for each row execute function sf_prevent_mutation();

create table paper_executions (
  id                 uuid primary key,
  order_id           uuid not null references paper_orders(id),
  portfolio_id       uuid not null references portfolios(id),
  instrument_id      uuid not null references instruments(id),
  fill_seq           int not null,
  side               text not null,
  quantity           numeric(38,18) not null check (quantity > 0),
  price              numeric(38,12) not null check (price > 0),
  reference_price    numeric(38,12) not null,
  notional           numeric(38,12) not null,
  commission         numeric(38,12) not null check (commission >= 0),
  spread_cost        numeric(38,12) not null,
  slippage_cost      numeric(38,12) not null,
  realized_pnl       numeric(38,12) not null default 0,
  liquidity_cap      numeric(38,18),
  liquidity_model    text not null,
  market_snapshot_id uuid not null references market_snapshots(id),
  journal_id         uuid not null references ledger_journals(id),
  executed_at        timestamptz not null,
  recorded_at        timestamptz not null,
  unique (order_id, fill_seq)
);
create index paper_executions_portfolio_idx on paper_executions (portfolio_id, executed_at desc);
create trigger paper_executions_append_only before update or delete on paper_executions for each row execute function sf_prevent_mutation();

create table portfolio_equity_snapshots (
  id           bigserial primary key,
  portfolio_id uuid not null references portfolios(id),
  at           timestamptz not null,
  equity       numeric(38,12) not null,
  cash         numeric(38,12) not null,
  positions_value numeric(38,12) not null,
  priced       boolean not null,
  source       text not null
);
create index portfolio_equity_snapshots_idx on portfolio_equity_snapshots (portfolio_id, at);

create table reconciliation_runs (
  id           uuid primary key,
  portfolio_id uuid not null references portfolios(id),
  run_at       timestamptz not null,
  status       text not null check (status in ('OK', 'FAILED')),
  checks       jsonb not null
);
create index reconciliation_runs_idx on reconciliation_runs (portfolio_id, run_at desc);

-- Corporate-action entitlements applied to portfolios (split once; dividend entitled at ex-date, paid at pay date).
create table corporate_action_applications (
  portfolio_id      uuid not null references portfolios(id),
  action_id         uuid not null references corporate_actions(id),
  status            text not null check (status in ('APPLIED', 'ENTITLED', 'PAID')),
  entitled_quantity numeric(38,18),
  amount            numeric(38,12),
  journal_id        uuid references ledger_journals(id),
  applied_at        timestamptz not null,
  primary key (portfolio_id, action_id)
);

create table borrow_accruals (
  portfolio_id  uuid not null references portfolios(id),
  instrument_id uuid not null references instruments(id),
  accrual_date  date not null,
  amount        numeric(38,12) not null,
  journal_id    uuid not null references ledger_journals(id),
  primary key (portfolio_id, instrument_id, accrual_date)
);

-- Every risk evaluation with inputs, rule results and final decision (FR-093). Append-only.
create table risk_evaluations (
  id             uuid primary key,
  portfolio_id   uuid not null references portfolios(id),
  instrument_id  uuid not null references instruments(id),
  strategy_id    uuid,
  source         text not null,
  intent         jsonb not null,
  inputs         jsonb not null,
  rule_results   jsonb not null,
  decision       text not null check (decision in ('ALLOW', 'BLOCK')),
  blocking_rules text[] not null default '{}',
  market_time    timestamptz not null,
  created_at     timestamptz not null,
  correlation_id text,
  duration_ms    bigint not null
);
create index risk_evaluations_portfolio_idx on risk_evaluations (portfolio_id, created_at desc);
create trigger risk_evaluations_append_only before update or delete on risk_evaluations for each row execute function sf_prevent_mutation();
alter table paper_orders add constraint paper_orders_risk_fk foreign key (risk_evaluation_id) references risk_evaluations(id);
