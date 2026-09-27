-- WP5: backtests with dataset provenance, integrity, metrics, series and trades.

create table backtests (
  id                uuid primary key,
  strategy_id       uuid not null references strategies(id),
  version_id        uuid not null references strategy_versions(id),
  content_hash      text not null,
  status            text not null check (status in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
  result_status     text check (result_status in ('OK', 'WARNINGS', 'CRITICAL', 'MANUAL_REVIEW_REQUIRED')),
  params            jsonb not null,
  dataset           jsonb,
  integrity         jsonb,
  metrics           jsonb,
  equity_series     jsonb,
  drawdown_series   jsonb,
  benchmark         jsonb,
  error             text,
  created_at        timestamptz not null,
  started_at        timestamptz,
  completed_at      timestamptz
);
create index backtests_strategy_idx on backtests (strategy_id, created_at desc);

create table backtest_trades (
  id            bigserial primary key,
  backtest_id   uuid not null references backtests(id),
  symbol        text not null,
  side          text not null,
  entry_time    timestamptz not null,
  entry_price   numeric(38,12) not null,
  exit_time     timestamptz,
  exit_price    numeric(38,12),
  quantity      numeric(38,18) not null,
  gross_pnl     numeric(38,12) not null,
  fees          numeric(38,12) not null,
  spread_cost   numeric(38,12) not null,
  slippage_cost numeric(38,12) not null,
  borrow_cost   numeric(38,12) not null,
  dividends     numeric(38,12) not null,
  net_pnl       numeric(38,12) not null,
  holding_bars  int not null,
  exit_reason   text not null,
  partial_fill  boolean not null
);
create index backtest_trades_idx on backtest_trades (backtest_id, entry_time);
