-- On-device schema (D-027), translated from the Version 1 PostgreSQL migrations.
-- Conventions: ids are UUID text; money, prices and quantities are exact decimal TEXT (never REAL,
-- NFR-002) and are only summed in Kotlin with BigDecimal; timestamps are epoch milliseconds (UTC).
-- Append-only tables reject UPDATE/DELETE with triggers, exactly like the server schema.

CREATE TABLE settings (
  key   TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE audit_events (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id       TEXT NOT NULL UNIQUE,
  occurred_at    INTEGER NOT NULL,
  actor          TEXT NOT NULL,
  category       TEXT NOT NULL,
  action         TEXT NOT NULL,
  outcome        TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'BLOCKED', 'DENIED')),
  entity_type    TEXT,
  entity_id      TEXT,
  details        TEXT NOT NULL,
  prev_hash      TEXT,
  hash           TEXT NOT NULL
);
CREATE INDEX audit_events_entity_idx ON audit_events (entity_type, entity_id);
CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events BEGIN SELECT RAISE(ABORT, 'append-only table audit_events'); END;
CREATE TRIGGER audit_events_no_delete BEFORE DELETE ON audit_events BEGIN SELECT RAISE(ABORT, 'append-only table audit_events'); END;

CREATE TABLE system_health_events (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at INTEGER NOT NULL,
  component   TEXT NOT NULL,
  status      TEXT NOT NULL CHECK (status IN ('OK', 'DEGRADED', 'FAILED', 'UNKNOWN')),
  detail      TEXT
);

CREATE TABLE action_keys (
  key        TEXT NOT NULL PRIMARY KEY,
  result     TEXT,
  created_at INTEGER NOT NULL
);

-- ------------------------------------------------------------------ market data

CREATE TABLE instruments (
  id                 TEXT NOT NULL PRIMARY KEY,
  symbol             TEXT NOT NULL UNIQUE,
  asset_class        TEXT NOT NULL CHECK (asset_class IN ('US_EQUITY', 'CRYPTO')),
  name               TEXT NOT NULL,
  exchange           TEXT NOT NULL,
  currency           TEXT NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
  price_increment    TEXT NOT NULL,
  quantity_increment TEXT NOT NULL,
  min_quantity       TEXT NOT NULL,
  shortable          INTEGER NOT NULL DEFAULT 0,
  active             INTEGER NOT NULL DEFAULT 1,
  source             TEXT NOT NULL CHECK (source IN ('SEED', 'PROVIDER_LOOKUP')),
  version            INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE candles (
  instrument_id TEXT NOT NULL REFERENCES instruments(id),
  timeframe     TEXT NOT NULL,
  open_time     INTEGER NOT NULL,
  open          TEXT NOT NULL,
  high          TEXT NOT NULL,
  low           TEXT NOT NULL,
  close         TEXT NOT NULL,
  volume        TEXT NOT NULL,
  provider      TEXT NOT NULL,
  feed_type     TEXT NOT NULL,
  received_at   INTEGER NOT NULL,
  PRIMARY KEY (instrument_id, timeframe, open_time)
);

CREATE TABLE latest_quotes (
  instrument_id TEXT NOT NULL PRIMARY KEY REFERENCES instruments(id),
  bid           TEXT,
  ask           TEXT,
  last          TEXT NOT NULL,
  bid_size      TEXT,
  ask_size      TEXT,
  exchange_ts   INTEGER NOT NULL,
  received_at   INTEGER NOT NULL,
  provider      TEXT NOT NULL,
  feed_type     TEXT NOT NULL
);

CREATE TABLE market_snapshots (
  id                TEXT NOT NULL PRIMARY KEY,
  instrument_id     TEXT NOT NULL REFERENCES instruments(id),
  captured_at       INTEGER NOT NULL,
  market_time       INTEGER NOT NULL,
  provider          TEXT NOT NULL,
  feed_type         TEXT NOT NULL,
  bid               TEXT,
  ask               TEXT,
  last              TEXT,
  quote_ts          INTEGER,
  quote_age_seconds INTEGER,
  freshness         TEXT NOT NULL,
  detail            TEXT NOT NULL DEFAULT '{}'
);
CREATE TRIGGER market_snapshots_no_update BEFORE UPDATE ON market_snapshots BEGIN SELECT RAISE(ABORT, 'append-only table market_snapshots'); END;

CREATE TABLE market_data_status (
  instrument_id TEXT NOT NULL REFERENCES instruments(id),
  timeframe     TEXT NOT NULL,
  status        TEXT NOT NULL,
  detail        TEXT,
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (instrument_id, timeframe)
);

CREATE TABLE fx_rates (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  base        TEXT NOT NULL,
  quote       TEXT NOT NULL,
  rate        TEXT NOT NULL,
  as_of       INTEGER NOT NULL,
  provider    TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  UNIQUE (base, quote, as_of, provider)
);

CREATE TABLE corporate_actions (
  id            TEXT NOT NULL PRIMARY KEY,
  instrument_id TEXT NOT NULL REFERENCES instruments(id),
  action_type   TEXT NOT NULL CHECK (action_type IN ('SPLIT', 'CASH_DIVIDEND')),
  ex_date       TEXT NOT NULL,
  pay_date      TEXT,
  ratio_new     TEXT,
  ratio_old     TEXT,
  cash_amount   TEXT,
  provider      TEXT NOT NULL,
  received_at   INTEGER NOT NULL,
  UNIQUE (instrument_id, action_type, ex_date)
);

CREATE TABLE corporate_action_coverage (
  instrument_id TEXT NOT NULL PRIMARY KEY REFERENCES instruments(id),
  status        TEXT NOT NULL CHECK (status IN ('AVAILABLE', 'UNAVAILABLE')),
  covered_from  TEXT,
  covered_to    TEXT,
  provider      TEXT NOT NULL,
  detail        TEXT,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE watchlists (
  id          TEXT NOT NULL PRIMARY KEY,
  name        TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  archived_at INTEGER,
  version     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE watchlist_items (
  watchlist_id  TEXT NOT NULL REFERENCES watchlists(id),
  instrument_id TEXT NOT NULL REFERENCES instruments(id),
  added_at      INTEGER NOT NULL,
  PRIMARY KEY (watchlist_id, instrument_id)
);

CREATE TABLE price_alerts (
  id              TEXT NOT NULL PRIMARY KEY,
  instrument_id   TEXT NOT NULL REFERENCES instruments(id),
  condition       TEXT NOT NULL CHECK (condition IN ('ABOVE', 'BELOW')),
  threshold       TEXT NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('ACTIVE', 'TRIGGERED', 'CANCELLED')),
  note            TEXT,
  created_at      INTEGER NOT NULL,
  triggered_at    INTEGER,
  triggered_price TEXT,
  version         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE replay_state (
  singleton    INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
  market_time  INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);

-- ------------------------------------------------------------------ portfolios and ledger

CREATE TABLE portfolios (
  id                     TEXT NOT NULL PRIMARY KEY,
  name                   TEXT NOT NULL,
  account_type           TEXT NOT NULL DEFAULT 'PAPER' CHECK (account_type = 'PAPER'),
  status                 TEXT NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
  base_currency          TEXT NOT NULL DEFAULT 'USD' CHECK (base_currency = 'USD'),
  starting_balance       TEXT NOT NULL,
  cost_model             TEXT NOT NULL,
  shorting_enabled       INTEGER NOT NULL DEFAULT 0,
  cloned_from            TEXT REFERENCES portfolios(id),
  reset_from             TEXT REFERENCES portfolios(id),
  reconciliation_status  TEXT NOT NULL DEFAULT 'OK' CHECK (reconciliation_status IN ('OK', 'FAILED', 'UNVERIFIED')),
  created_at             INTEGER NOT NULL,
  archived_at            INTEGER,
  version                INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX portfolios_active_name ON portfolios (lower(name)) WHERE status = 'ACTIVE';

CREATE TABLE ledger_journals (
  id             TEXT NOT NULL PRIMARY KEY,
  seq            INTEGER NOT NULL UNIQUE,
  portfolio_id   TEXT NOT NULL REFERENCES portfolios(id),
  journal_type   TEXT NOT NULL CHECK (journal_type IN ('FUNDING', 'RESERVATION', 'RESERVATION_RELEASE', 'EXECUTION', 'FEE', 'BORROW_FEE',
                   'DIVIDEND', 'SPLIT', 'ADJUSTMENT')),
  reference_type TEXT,
  reference_id   TEXT,
  occurred_at    INTEGER NOT NULL,
  recorded_at    INTEGER NOT NULL,
  description    TEXT NOT NULL
);
CREATE INDEX ledger_journals_portfolio_idx ON ledger_journals (portfolio_id, seq);
CREATE TRIGGER ledger_journals_no_update BEFORE UPDATE ON ledger_journals BEGIN SELECT RAISE(ABORT, 'append-only table ledger_journals'); END;
CREATE TRIGGER ledger_journals_no_delete BEFORE DELETE ON ledger_journals BEGIN SELECT RAISE(ABORT, 'append-only table ledger_journals'); END;

CREATE TABLE ledger_entries (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  journal_id    TEXT NOT NULL REFERENCES ledger_journals(id),
  portfolio_id  TEXT NOT NULL REFERENCES portfolios(id),
  account       TEXT NOT NULL CHECK (account IN ('CASH', 'CASH_RESERVED', 'POSITION', 'OWNER_CAPITAL', 'REALIZED_PNL', 'FEES',
                  'BORROW_FEES', 'DIVIDENDS')),
  instrument_id TEXT REFERENCES instruments(id),
  amount        TEXT NOT NULL,
  quantity      TEXT NOT NULL DEFAULT '0',
  CHECK ((account = 'POSITION') = (instrument_id IS NOT NULL))
);
CREATE INDEX ledger_entries_portfolio_idx ON ledger_entries (portfolio_id, account, instrument_id);
CREATE INDEX ledger_entries_journal_idx ON ledger_entries (journal_id);
CREATE TRIGGER ledger_entries_no_update BEFORE UPDATE ON ledger_entries BEGIN SELECT RAISE(ABORT, 'append-only table ledger_entries'); END;
CREATE TRIGGER ledger_entries_no_delete BEFORE DELETE ON ledger_entries BEGIN SELECT RAISE(ABORT, 'append-only table ledger_entries'); END;

CREATE TABLE position_lots (
  id                 TEXT NOT NULL PRIMARY KEY,
  portfolio_id       TEXT NOT NULL REFERENCES portfolios(id),
  instrument_id      TEXT NOT NULL REFERENCES instruments(id),
  side               TEXT NOT NULL CHECK (side IN ('LONG', 'SHORT')),
  opened_at          INTEGER NOT NULL,
  open_execution_id  TEXT,
  quantity_open      TEXT NOT NULL,
  quantity_remaining TEXT NOT NULL,
  cost_remaining     TEXT NOT NULL,
  closed_at          INTEGER
);
CREATE INDEX position_lots_open_idx ON position_lots (portfolio_id, instrument_id, opened_at) WHERE closed_at IS NULL;

CREATE TABLE risk_evaluations (
  id             TEXT NOT NULL PRIMARY KEY,
  portfolio_id   TEXT NOT NULL REFERENCES portfolios(id),
  instrument_id  TEXT NOT NULL REFERENCES instruments(id),
  strategy_id    TEXT,
  source         TEXT NOT NULL,
  intent         TEXT NOT NULL,
  inputs         TEXT NOT NULL,
  rule_results   TEXT NOT NULL,
  decision       TEXT NOT NULL CHECK (decision IN ('ALLOW', 'BLOCK')),
  blocking_rules TEXT NOT NULL,
  market_time    INTEGER NOT NULL,
  created_at     INTEGER NOT NULL,
  duration_ms    INTEGER NOT NULL
);
CREATE INDEX risk_evaluations_portfolio_idx ON risk_evaluations (portfolio_id, created_at);
CREATE TRIGGER risk_evaluations_no_update BEFORE UPDATE ON risk_evaluations BEGIN SELECT RAISE(ABORT, 'append-only table risk_evaluations'); END;
CREATE TRIGGER risk_evaluations_no_delete BEFORE DELETE ON risk_evaluations BEGIN SELECT RAISE(ABORT, 'append-only table risk_evaluations'); END;

CREATE TABLE paper_orders (
  id                   TEXT NOT NULL PRIMARY KEY,
  portfolio_id         TEXT NOT NULL REFERENCES portfolios(id),
  instrument_id        TEXT NOT NULL REFERENCES instruments(id),
  side                 TEXT NOT NULL CHECK (side IN ('BUY', 'SELL', 'SELL_SHORT', 'BUY_TO_COVER')),
  order_type           TEXT NOT NULL CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT')),
  time_in_force        TEXT NOT NULL CHECK (time_in_force IN ('DAY', 'GTC')),
  quantity             TEXT NOT NULL,
  limit_price          TEXT,
  stop_price           TEXT,
  status               TEXT NOT NULL CHECK (status IN ('CREATED', 'VALIDATED', 'REJECTED', 'PENDING', 'PARTIALLY_FILLED', 'FILLED',
                         'CANCELLED', 'EXPIRED', 'FAILED')),
  source               TEXT NOT NULL CHECK (source IN ('MANUAL', 'RECOMMENDATION', 'AUTONOMOUS', 'EMERGENCY_CLOSE', 'FORCED_COVER', 'SYSTEM')),
  venue                TEXT NOT NULL DEFAULT 'PAPER_SIMULATOR' CHECK (venue = 'PAPER_SIMULATOR'),
  strategy_id          TEXT,
  strategy_version_id  TEXT,
  recommendation_id    TEXT UNIQUE,
  signal_id            TEXT,
  market_snapshot_id   TEXT REFERENCES market_snapshots(id),
  risk_evaluation_id   TEXT REFERENCES risk_evaluations(id),
  filled_quantity      TEXT NOT NULL DEFAULT '0',
  average_fill_price   TEXT,
  reserved_amount      TEXT NOT NULL DEFAULT '0',
  reservation_released TEXT NOT NULL DEFAULT '0',
  triggered            INTEGER NOT NULL DEFAULT 0,
  last_fill_quote_ts   INTEGER,
  rejection_reason     TEXT,
  created_at           INTEGER NOT NULL,
  eligible_at          INTEGER NOT NULL,
  expires_at           INTEGER NOT NULL,
  updated_at           INTEGER NOT NULL,
  version              INTEGER NOT NULL DEFAULT 0,
  CHECK (order_type NOT IN ('LIMIT', 'STOP_LIMIT') OR limit_price IS NOT NULL),
  CHECK (order_type NOT IN ('STOP', 'STOP_LIMIT') OR stop_price IS NOT NULL),
  CHECK (source NOT IN ('RECOMMENDATION', 'AUTONOMOUS') OR strategy_version_id IS NOT NULL)
);
CREATE INDEX paper_orders_open_idx ON paper_orders (status, eligible_at);
CREATE INDEX paper_orders_portfolio_idx ON paper_orders (portfolio_id, created_at);

CREATE TABLE order_status_history (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  order_id    TEXT NOT NULL REFERENCES paper_orders(id),
  from_status TEXT,
  to_status   TEXT NOT NULL,
  at          INTEGER NOT NULL,
  market_time INTEGER NOT NULL,
  reason      TEXT
);
CREATE INDEX order_status_history_order_idx ON order_status_history (order_id, id);
CREATE TRIGGER order_status_history_no_update BEFORE UPDATE ON order_status_history BEGIN SELECT RAISE(ABORT, 'append-only table order_status_history'); END;
CREATE TRIGGER order_status_history_no_delete BEFORE DELETE ON order_status_history BEGIN SELECT RAISE(ABORT, 'append-only table order_status_history'); END;

CREATE TABLE paper_executions (
  id                 TEXT NOT NULL PRIMARY KEY,
  order_id           TEXT NOT NULL REFERENCES paper_orders(id),
  portfolio_id       TEXT NOT NULL REFERENCES portfolios(id),
  instrument_id      TEXT NOT NULL REFERENCES instruments(id),
  fill_seq           INTEGER NOT NULL,
  side               TEXT NOT NULL,
  quantity           TEXT NOT NULL,
  price              TEXT NOT NULL,
  reference_price    TEXT NOT NULL,
  notional           TEXT NOT NULL,
  commission         TEXT NOT NULL,
  spread_cost        TEXT NOT NULL,
  slippage_cost      TEXT NOT NULL,
  realized_pnl       TEXT NOT NULL DEFAULT '0',
  liquidity_cap      TEXT,
  liquidity_model    TEXT NOT NULL,
  market_snapshot_id TEXT NOT NULL REFERENCES market_snapshots(id),
  journal_id         TEXT NOT NULL REFERENCES ledger_journals(id),
  executed_at        INTEGER NOT NULL,
  recorded_at        INTEGER NOT NULL,
  UNIQUE (order_id, fill_seq)
);
CREATE INDEX paper_executions_portfolio_idx ON paper_executions (portfolio_id, executed_at);
CREATE TRIGGER paper_executions_no_update BEFORE UPDATE ON paper_executions BEGIN SELECT RAISE(ABORT, 'append-only table paper_executions'); END;
CREATE TRIGGER paper_executions_no_delete BEFORE DELETE ON paper_executions BEGIN SELECT RAISE(ABORT, 'append-only table paper_executions'); END;

CREATE TABLE portfolio_equity_snapshots (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  portfolio_id    TEXT NOT NULL REFERENCES portfolios(id),
  at              INTEGER NOT NULL,
  equity          TEXT NOT NULL,
  cash            TEXT NOT NULL,
  positions_value TEXT NOT NULL,
  priced          INTEGER NOT NULL,
  source          TEXT NOT NULL
);
CREATE INDEX portfolio_equity_snapshots_idx ON portfolio_equity_snapshots (portfolio_id, at);

CREATE TABLE reconciliation_runs (
  id           TEXT NOT NULL PRIMARY KEY,
  portfolio_id TEXT NOT NULL REFERENCES portfolios(id),
  run_at       INTEGER NOT NULL,
  status       TEXT NOT NULL CHECK (status IN ('OK', 'FAILED')),
  checks       TEXT NOT NULL
);
CREATE INDEX reconciliation_runs_idx ON reconciliation_runs (portfolio_id, run_at);

CREATE TABLE corporate_action_applications (
  portfolio_id      TEXT NOT NULL REFERENCES portfolios(id),
  action_id         TEXT NOT NULL REFERENCES corporate_actions(id),
  status            TEXT NOT NULL CHECK (status IN ('APPLIED', 'ENTITLED', 'PAID')),
  entitled_quantity TEXT,
  amount            TEXT,
  journal_id        TEXT REFERENCES ledger_journals(id),
  applied_at        INTEGER NOT NULL,
  PRIMARY KEY (portfolio_id, action_id)
);

CREATE TABLE borrow_accruals (
  portfolio_id  TEXT NOT NULL REFERENCES portfolios(id),
  instrument_id TEXT NOT NULL REFERENCES instruments(id),
  accrual_date  TEXT NOT NULL,
  amount        TEXT NOT NULL,
  journal_id    TEXT NOT NULL REFERENCES ledger_journals(id),
  PRIMARY KEY (portfolio_id, instrument_id, accrual_date)
);

-- ------------------------------------------------------------------ strategies and backtests

CREATE TABLE strategies (
  id                 TEXT NOT NULL PRIMARY KEY,
  name               TEXT NOT NULL,
  asset_class        TEXT NOT NULL CHECK (asset_class IN ('US_EQUITY', 'CRYPTO')),
  status             TEXT NOT NULL CHECK (status IN ('DRAFT', 'VALIDATION_FAILED', 'MANUAL_REVIEW_REQUIRED', 'VALIDATED', 'BACKTESTED',
                       'PAPER_ELIGIBLE', 'ACTIVE_RECOMMENDATION', 'ACTIVE_AUTONOMOUS', 'PAUSED', 'SUSPENDED', 'ARCHIVED')),
  status_reason      TEXT,
  current_version_id TEXT,
  cloned_from        TEXT REFERENCES strategies(id),
  created_at         INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL,
  archived_at        INTEGER,
  version            INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE strategy_versions (
  id                TEXT NOT NULL PRIMARY KEY,
  strategy_id       TEXT NOT NULL REFERENCES strategies(id),
  version_number    INTEGER NOT NULL,
  schema_version    TEXT NOT NULL,
  content           TEXT NOT NULL,
  canonical_content TEXT NOT NULL,
  content_hash      TEXT NOT NULL,
  source            TEXT NOT NULL CHECK (source IN ('OWNER', 'IMPORT', 'AI_COMPILED', 'CLONE')),
  source_ref        TEXT,
  validation_status TEXT NOT NULL CHECK (validation_status IN ('PENDING', 'VALIDATED', 'VALIDATION_FAILED', 'MANUAL_REVIEW_REQUIRED')),
  created_at        INTEGER NOT NULL,
  UNIQUE (strategy_id, version_number)
);
CREATE INDEX strategy_versions_hash_idx ON strategy_versions (content_hash);
CREATE TRIGGER strategy_versions_no_delete BEFORE DELETE ON strategy_versions BEGIN SELECT RAISE(ABORT, 'strategy versions are immutable'); END;
CREATE TRIGGER strategy_versions_immutable BEFORE UPDATE OF content, canonical_content, content_hash, strategy_id, version_number, source ON strategy_versions
BEGIN SELECT RAISE(ABORT, 'strategy version content is immutable'); END;

CREATE TABLE strategy_validations (
  id                TEXT NOT NULL PRIMARY KEY,
  version_id        TEXT NOT NULL REFERENCES strategy_versions(id),
  status            TEXT NOT NULL CHECK (status IN ('VALIDATED', 'VALIDATION_FAILED', 'MANUAL_REVIEW_REQUIRED')),
  issues            TEXT NOT NULL,
  unknown_fields    TEXT NOT NULL,
  provider          TEXT,
  validator_version TEXT NOT NULL,
  validated_at      INTEGER NOT NULL
);
CREATE INDEX strategy_validations_version_idx ON strategy_validations (version_id, validated_at);
CREATE TRIGGER strategy_validations_no_update BEFORE UPDATE ON strategy_validations BEGIN SELECT RAISE(ABORT, 'append-only table strategy_validations'); END;

CREATE TABLE strategy_imports (
  id          TEXT NOT NULL PRIMARY KEY,
  received_at INTEGER NOT NULL,
  size_bytes  INTEGER NOT NULL,
  sha256      TEXT NOT NULL,
  filename    TEXT,
  outcome     TEXT NOT NULL CHECK (outcome IN ('VALIDATED', 'VALIDATION_FAILED', 'MANUAL_REVIEW_REQUIRED', 'REJECTED')),
  strategy_id TEXT REFERENCES strategies(id),
  version_id  TEXT REFERENCES strategy_versions(id),
  issues      TEXT NOT NULL,
  original    TEXT
);

CREATE TABLE strategy_status_history (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  strategy_id TEXT NOT NULL REFERENCES strategies(id),
  from_status TEXT,
  to_status   TEXT NOT NULL,
  reason      TEXT,
  at          INTEGER NOT NULL
);
CREATE TRIGGER strategy_status_history_no_update BEFORE UPDATE ON strategy_status_history BEGIN SELECT RAISE(ABORT, 'append-only table strategy_status_history'); END;

CREATE TABLE backtests (
  id                TEXT NOT NULL PRIMARY KEY,
  strategy_id       TEXT NOT NULL REFERENCES strategies(id),
  version_id        TEXT NOT NULL REFERENCES strategy_versions(id),
  content_hash      TEXT NOT NULL,
  status            TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
  result_status     TEXT CHECK (result_status IN ('OK', 'WARNINGS', 'CRITICAL', 'MANUAL_REVIEW_REQUIRED')),
  params            TEXT NOT NULL,
  dataset           TEXT,
  integrity         TEXT,
  metrics           TEXT,
  equity_series     TEXT,
  drawdown_series   TEXT,
  benchmark         TEXT,
  error             TEXT,
  created_at        INTEGER NOT NULL,
  started_at        INTEGER,
  completed_at      INTEGER
);
CREATE INDEX backtests_strategy_idx ON backtests (strategy_id, created_at);

CREATE TABLE backtest_trades (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  backtest_id   TEXT NOT NULL REFERENCES backtests(id),
  symbol        TEXT NOT NULL,
  side          TEXT NOT NULL,
  entry_time    INTEGER NOT NULL,
  entry_price   TEXT NOT NULL,
  exit_time     INTEGER,
  exit_price    TEXT,
  quantity      TEXT NOT NULL,
  gross_pnl     TEXT NOT NULL,
  fees          TEXT NOT NULL,
  spread_cost   TEXT NOT NULL,
  slippage_cost TEXT NOT NULL,
  borrow_cost   TEXT NOT NULL,
  dividends     TEXT NOT NULL,
  net_pnl       TEXT NOT NULL,
  holding_bars  INTEGER NOT NULL,
  exit_reason   TEXT NOT NULL,
  partial_fill  INTEGER NOT NULL
);
CREATE INDEX backtest_trades_idx ON backtest_trades (backtest_id, entry_time);

-- ------------------------------------------------------------------ risk, signals and recommendations

CREATE TABLE risk_profiles (
  id         TEXT NOT NULL PRIMARY KEY,
  scope      TEXT NOT NULL CHECK (scope IN ('GLOBAL', 'PORTFOLIO', 'STRATEGY')),
  scope_id   TEXT,
  limits     TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  version    INTEGER NOT NULL DEFAULT 0,
  CHECK ((scope = 'GLOBAL') = (scope_id IS NULL))
);
CREATE UNIQUE INDEX risk_profiles_scope_idx ON risk_profiles (scope, coalesce(scope_id, ''));

CREATE TABLE emergency_state (
  singleton             INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
  pause_all             INTEGER NOT NULL DEFAULT 0,
  prevent_new_positions INTEGER NOT NULL DEFAULT 0,
  reason                TEXT,
  updated_at            INTEGER NOT NULL,
  version               INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE strategy_activations (
  id                 TEXT NOT NULL PRIMARY KEY,
  strategy_id        TEXT NOT NULL REFERENCES strategies(id),
  version_id         TEXT NOT NULL REFERENCES strategy_versions(id),
  content_hash       TEXT NOT NULL,
  portfolio_id       TEXT NOT NULL REFERENCES portfolios(id),
  mode               TEXT NOT NULL CHECK (mode IN ('RECOMMENDATION', 'AUTONOMOUS')),
  allocation_percent TEXT NOT NULL,
  backtest_id        TEXT NOT NULL REFERENCES backtests(id),
  disclosure_version TEXT,
  authorized_at      INTEGER,
  fingerprint        TEXT,
  status             TEXT NOT NULL CHECK (status IN ('ACTIVE', 'ENDED')),
  created_at         INTEGER NOT NULL,
  ended_at           INTEGER,
  end_reason         TEXT
);
CREATE UNIQUE INDEX strategy_activations_one_active ON strategy_activations (strategy_id) WHERE status = 'ACTIVE';

CREATE TABLE strategy_evaluations (
  id            TEXT NOT NULL PRIMARY KEY,
  strategy_id   TEXT NOT NULL REFERENCES strategies(id),
  activation_id TEXT NOT NULL REFERENCES strategy_activations(id),
  bucket_start  INTEGER NOT NULL,
  status        TEXT NOT NULL CHECK (status IN ('CLAIMED', 'COMPLETED', 'BLOCKED', 'FAILED')),
  detail        TEXT NOT NULL DEFAULT '{}',
  started_at    INTEGER NOT NULL,
  completed_at  INTEGER,
  UNIQUE (strategy_id, bucket_start)
);

CREATE TABLE signals (
  id                 TEXT NOT NULL PRIMARY KEY,
  strategy_id        TEXT NOT NULL REFERENCES strategies(id),
  version_id         TEXT NOT NULL REFERENCES strategy_versions(id),
  content_hash       TEXT NOT NULL,
  activation_id      TEXT NOT NULL REFERENCES strategy_activations(id),
  evaluation_id      TEXT NOT NULL REFERENCES strategy_evaluations(id),
  portfolio_id       TEXT NOT NULL REFERENCES portfolios(id),
  instrument_id      TEXT NOT NULL REFERENCES instruments(id),
  bucket_start       INTEGER NOT NULL,
  action             TEXT NOT NULL CHECK (action IN ('ENTER_LONG', 'EXIT_LONG', 'ENTER_SHORT', 'EXIT_SHORT')),
  side               TEXT NOT NULL,
  quantity           TEXT NOT NULL,
  order_type         TEXT NOT NULL,
  limit_price        TEXT,
  reference_price    TEXT NOT NULL,
  market_snapshot_id TEXT NOT NULL REFERENCES market_snapshots(id),
  triggered_rules    TEXT NOT NULL,
  rationale          TEXT NOT NULL,
  expires_at         INTEGER NOT NULL,
  disposition        TEXT NOT NULL CHECK (disposition IN ('RECOMMENDED', 'AUTO_EXECUTED', 'BLOCKED')),
  risk_evaluation_id TEXT REFERENCES risk_evaluations(id),
  created_at         INTEGER NOT NULL,
  UNIQUE (strategy_id, instrument_id, bucket_start, action)
);
CREATE TRIGGER signals_no_delete BEFORE DELETE ON signals BEGIN SELECT RAISE(ABORT, 'append-only table signals'); END;

CREATE TABLE recommendations (
  id                    TEXT NOT NULL PRIMARY KEY,
  signal_id             TEXT NOT NULL UNIQUE REFERENCES signals(id),
  strategy_id           TEXT NOT NULL REFERENCES strategies(id),
  portfolio_id          TEXT NOT NULL REFERENCES portfolios(id),
  instrument_id         TEXT NOT NULL REFERENCES instruments(id),
  status                TEXT NOT NULL CHECK (status IN ('CREATED', 'BLOCKED', 'PENDING', 'ACCEPTED', 'MODIFIED', 'DECLINED', 'EXPIRED', 'SUPERSEDED', 'FAILED')),
  status_reason         TEXT,
  side                  TEXT NOT NULL,
  quantity              TEXT NOT NULL,
  order_type            TEXT NOT NULL,
  limit_price           TEXT,
  reference_price       TEXT NOT NULL,
  max_deviation_percent TEXT NOT NULL,
  expires_at            INTEGER NOT NULL,
  snoozed_until         INTEGER,
  risk_evaluation_id    TEXT REFERENCES risk_evaluations(id),
  order_id              TEXT UNIQUE REFERENCES paper_orders(id),
  created_at            INTEGER NOT NULL,
  updated_at            INTEGER NOT NULL,
  decided_at            INTEGER,
  version               INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX recommendations_status_idx ON recommendations (status, expires_at);

CREATE TABLE recommendation_decisions (
  id                TEXT NOT NULL PRIMARY KEY,
  recommendation_id TEXT NOT NULL REFERENCES recommendations(id),
  decision          TEXT NOT NULL CHECK (decision IN ('CREATE', 'BLOCK', 'ACCEPT', 'MODIFY', 'DECLINE', 'SNOOZE', 'PAUSE_STRATEGY', 'EXPIRE', 'SUPERSEDE', 'FAIL')),
  actor             TEXT NOT NULL,
  details           TEXT NOT NULL DEFAULT '{}',
  at                INTEGER NOT NULL,
  market_time       INTEGER NOT NULL
);
CREATE INDEX recommendation_decisions_idx ON recommendation_decisions (recommendation_id, at);
CREATE TRIGGER recommendation_decisions_no_update BEFORE UPDATE ON recommendation_decisions BEGIN SELECT RAISE(ABORT, 'append-only table recommendation_decisions'); END;
CREATE TRIGGER recommendation_decisions_no_delete BEFORE DELETE ON recommendation_decisions BEGIN SELECT RAISE(ABORT, 'append-only table recommendation_decisions'); END;

-- ------------------------------------------------------------------ notifications (local inbox)

CREATE TABLE notification_events (
  id             TEXT NOT NULL PRIMARY KEY,
  category       TEXT NOT NULL,
  severity       TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  title          TEXT NOT NULL,
  body           TEXT NOT NULL,
  redacted_title TEXT NOT NULL,
  redacted_body  TEXT NOT NULL,
  entity_type    TEXT,
  entity_id      TEXT,
  deep_link      TEXT,
  dedupe_key     TEXT UNIQUE,
  shown          INTEGER NOT NULL DEFAULT 0,
  created_at     INTEGER NOT NULL,
  read_at        INTEGER
);
CREATE INDEX notification_events_created_idx ON notification_events (created_at, id);

-- ------------------------------------------------------------------ AI research

CREATE TABLE ai_providers (
  id            TEXT NOT NULL PRIMARY KEY,
  provider_type TEXT NOT NULL CHECK (provider_type IN ('GEMINI', 'OPENROUTER', 'OPENAI', 'ANTHROPIC')),
  display_name  TEXT NOT NULL,
  settings      TEXT NOT NULL,
  key_alias     TEXT,
  key_fingerprint TEXT,
  active        INTEGER NOT NULL DEFAULT 1,
  last_test_status TEXT,
  last_test_detail TEXT,
  last_test_at  INTEGER,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE ai_budget (
  singleton              INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
  monthly_cost_limit_usd TEXT NOT NULL,
  daily_request_limit    INTEGER NOT NULL,
  max_output_tokens      INTEGER NOT NULL,
  max_input_chars        INTEGER NOT NULL,
  updated_at             INTEGER NOT NULL,
  version                INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE research_sessions (
  id            TEXT NOT NULL PRIMARY KEY,
  title         TEXT NOT NULL,
  provider_id   TEXT NOT NULL REFERENCES ai_providers(id),
  provider_type TEXT NOT NULL,
  model         TEXT NOT NULL,
  asset_class   TEXT NOT NULL CHECK (asset_class IN ('US_EQUITY', 'CRYPTO')),
  universe      TEXT NOT NULL,
  horizon       TEXT NOT NULL,
  timeframe     TEXT NOT NULL,
  approach      TEXT NOT NULL,
  prompt        TEXT NOT NULL,
  retrieval     INTEGER NOT NULL,
  max_requests  INTEGER NOT NULL,
  max_cost_usd  TEXT NOT NULL,
  status        TEXT NOT NULL CHECK (status IN ('DRAFT', 'RUNNING', 'COMPLETED', 'FAILED')),
  review_status TEXT NOT NULL CHECK (review_status IN ('UNVERIFIED', 'REVIEWED', 'REJECTED')),
  reviewed_at   INTEGER,
  review_note   TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  version       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE research_runs (
  id                 TEXT NOT NULL PRIMARY KEY,
  session_id         TEXT NOT NULL REFERENCES research_sessions(id),
  purpose            TEXT NOT NULL CHECK (purpose IN ('RESEARCH', 'COMPILE')),
  provider_id        TEXT NOT NULL,
  provider_type      TEXT NOT NULL,
  model              TEXT NOT NULL,
  prompt_version     TEXT NOT NULL,
  system_prompt      TEXT NOT NULL,
  user_prompt        TEXT NOT NULL,
  parameters         TEXT NOT NULL,
  status             TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
  failure_code       TEXT,
  failure_detail     TEXT,
  response_text      TEXT,
  response_raw       TEXT,
  stop_reason        TEXT,
  input_tokens       INTEGER,
  output_tokens      INTEGER,
  search_requests    INTEGER,
  reserved_cost_usd  TEXT NOT NULL,
  estimated_cost_usd TEXT,
  sources_required   INTEGER NOT NULL,
  started_at         INTEGER NOT NULL,
  completed_at       INTEGER
);
CREATE INDEX research_runs_session_idx ON research_runs (session_id, started_at);
CREATE TRIGGER research_runs_no_delete BEFORE DELETE ON research_runs BEGIN SELECT RAISE(ABORT, 'research runs are append-only'); END;
CREATE TRIGGER research_runs_immutable BEFORE UPDATE ON research_runs WHEN old.status <> 'RUNNING'
BEGIN SELECT RAISE(ABORT, 'completed research runs are immutable'); END;

CREATE TABLE research_sources (
  id         TEXT NOT NULL PRIMARY KEY,
  run_id     TEXT NOT NULL REFERENCES research_runs(id),
  position   INTEGER NOT NULL,
  url        TEXT NOT NULL,
  title      TEXT,
  cited_text TEXT,
  page_age   TEXT,
  created_at INTEGER NOT NULL,
  UNIQUE (run_id, position)
);

CREATE TABLE research_edits (
  id          TEXT NOT NULL PRIMARY KEY,
  session_id  TEXT NOT NULL REFERENCES research_sessions(id),
  base_run_id TEXT NOT NULL REFERENCES research_runs(id),
  content     TEXT NOT NULL,
  note        TEXT,
  created_at  INTEGER NOT NULL,
  actor       TEXT NOT NULL
);

CREATE TABLE strategy_compilations (
  id               TEXT NOT NULL PRIMARY KEY,
  session_id       TEXT NOT NULL REFERENCES research_sessions(id),
  run_id           TEXT REFERENCES research_runs(id),
  source_edit_id   TEXT REFERENCES research_edits(id),
  source_run_id    TEXT NOT NULL REFERENCES research_runs(id),
  compiler_version TEXT NOT NULL,
  status           TEXT NOT NULL CHECK (status IN ('COMPILED', 'MANUAL_REVIEW_REQUIRED', 'VALIDATION_FAILED', 'REJECTED', 'FAILED')),
  candidate        TEXT,
  issues           TEXT NOT NULL DEFAULT '[]',
  strategy_id      TEXT REFERENCES strategies(id),
  version_id       TEXT REFERENCES strategy_versions(id),
  content_hash     TEXT,
  created_at       INTEGER NOT NULL
);

-- ------------------------------------------------------------------ seed data
-- Same instrument master, global risk defaults, emergency state and AI budget as the server
-- migrations. Instrument ids are name-based UUIDs of 'instrument:<symbol>' so they are stable.
INSERT INTO instruments(id, symbol, asset_class, name, exchange, price_increment, quantity_increment, min_quantity, shortable, source) VALUES
 ('3abe9252-5068-3657-abff-7839e7378784', 'AAPL', 'US_EQUITY', 'Apple Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('9b2973ff-d4bc-337b-844f-07df97315e61', 'MSFT', 'US_EQUITY', 'Microsoft Corporation', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('f14fbb56-49f1-3885-9b04-58156758b551', 'NVDA', 'US_EQUITY', 'NVIDIA Corporation', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('763a81a8-e959-3ae4-ae5d-324faf3d1831', 'AMZN', 'US_EQUITY', 'Amazon.com Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('65c522e7-6568-31c5-8c7f-cb52b1460115', 'GOOGL', 'US_EQUITY', 'Alphabet Inc. Class A', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('0c1c7b72-2bb2-308d-bfb4-42c726159298', 'META', 'US_EQUITY', 'Meta Platforms Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('22c0a2ed-01cc-320f-9622-6dd3a3a0a329', 'TSLA', 'US_EQUITY', 'Tesla Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('d40cf7c8-bbea-3878-93a3-0b194716b44f', 'BRK.B', 'US_EQUITY', 'Berkshire Hathaway Inc. Class B', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('6f7c5e0f-38a7-38d1-a2f2-78ba57971d78', 'JPM', 'US_EQUITY', 'JPMorgan Chase & Co.', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('9d8f0106-6d20-3452-89a0-47af2f85429c', 'V', 'US_EQUITY', 'Visa Inc.', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('f19d27b3-2e54-327e-adf0-e50f74827481', 'MA', 'US_EQUITY', 'Mastercard Inc.', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('a79461fb-b247-3561-bc58-5f7a1012e85c', 'UNH', 'US_EQUITY', 'UnitedHealth Group Inc.', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('1ad3974b-bf4a-30af-8eb6-3a7404df1fab', 'XOM', 'US_EQUITY', 'Exxon Mobil Corporation', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('ea2abdff-3be2-3033-a18a-8981455b36ff', 'JNJ', 'US_EQUITY', 'Johnson & Johnson', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('db7977ce-fb15-3fdd-8f95-a3506cc4c886', 'PG', 'US_EQUITY', 'Procter & Gamble Co.', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('ec927e5a-c9f3-3667-b47e-ff6c2bc2c44d', 'HD', 'US_EQUITY', 'Home Depot Inc.', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('44fb4532-e85c-3f55-a33a-3398e09b979c', 'COST', 'US_EQUITY', 'Costco Wholesale Corporation', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('f6d230f5-46a3-3453-8682-ce1f061ece51', 'AVGO', 'US_EQUITY', 'Broadcom Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('f10df9e3-d92d-34a2-bae0-b7d53d5fd004', 'LLY', 'US_EQUITY', 'Eli Lilly and Company', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('fb12a957-339e-3251-a6b4-fecd05e73e9b', 'WMT', 'US_EQUITY', 'Walmart Inc.', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('f39d26be-699a-30b6-b84b-be60a01c574d', 'KO', 'US_EQUITY', 'Coca-Cola Company', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('7fd9fcbd-7b41-3814-b5e5-966d2904ee12', 'PEP', 'US_EQUITY', 'PepsiCo Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('f8793bf3-c175-3077-9a51-6792a2fc6786', 'BAC', 'US_EQUITY', 'Bank of America Corporation', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('8e379cab-fed7-3857-9575-24c1aa55aaed', 'DIS', 'US_EQUITY', 'Walt Disney Company', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('5f3ed65c-db3d-30ea-9a81-eac04d820977', 'NFLX', 'US_EQUITY', 'Netflix Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('1f580816-9814-3289-8369-b2b9982933d4', 'AMD', 'US_EQUITY', 'Advanced Micro Devices Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('f4faf2d3-40e1-3f80-8d77-b29f0d2222e1', 'INTC', 'US_EQUITY', 'Intel Corporation', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('96de77de-36e5-3608-a59d-cf954b404b8a', 'CSCO', 'US_EQUITY', 'Cisco Systems Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('0f319a07-33ed-3935-ac3c-d249d04a7ba8', 'ORCL', 'US_EQUITY', 'Oracle Corporation', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('5c3d9f4f-e827-3b8a-9b9d-45cc4eb7f526', 'CRM', 'US_EQUITY', 'Salesforce Inc.', 'NYSE', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('d9dd72b3-f3fc-366e-a379-53fb797e510c', 'ADBE', 'US_EQUITY', 'Adobe Inc.', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('4b64653f-c2c5-3921-9d41-0c82b076b4c4', 'SPY', 'US_EQUITY', 'SPDR S&P 500 ETF Trust', 'NYSE ARCA', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('0231d600-f299-355e-8fa7-8ab4b117400f', 'QQQ', 'US_EQUITY', 'Invesco QQQ Trust', 'NASDAQ', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('e8e6b1d3-82af-30b1-846a-6cfd7dba1234', 'IWM', 'US_EQUITY', 'iShares Russell 2000 ETF', 'NYSE ARCA', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('85cce18d-fd8b-392b-b76c-90971acdadeb', 'DIA', 'US_EQUITY', 'SPDR Dow Jones Industrial Average ETF', 'NYSE ARCA', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('db559810-a482-3443-8032-25753f61b45d', 'VTI', 'US_EQUITY', 'Vanguard Total Stock Market ETF', 'NYSE ARCA', '0.01', '0.0001', '0.0001', 1, 'SEED'),
 ('37dc4588-bced-3c43-a1e7-b0be4f755123', 'BTC-USD', 'CRYPTO', 'Bitcoin / US Dollar', 'CRYPTO', '0.01', '0.00000001', '0.00000001', 0, 'SEED'),
 ('99f36291-8b6f-373b-beb4-b7cf59501c3d', 'ETH-USD', 'CRYPTO', 'Ether / US Dollar', 'CRYPTO', '0.01', '0.00000001', '0.00000001', 0, 'SEED'),
 ('4edf9d7e-1353-3c31-b476-d775b5615de4', 'SOL-USD', 'CRYPTO', 'Solana / US Dollar', 'CRYPTO', '0.001', '0.00000001', '0.00000001', 0, 'SEED'),
 ('26efa6d3-fc96-3234-a2cf-e871352ba985', 'XRP-USD', 'CRYPTO', 'XRP / US Dollar', 'CRYPTO', '0.0001', '0.000001', '0.000001', 0, 'SEED'),
 ('e44dce4f-9afc-35a3-b7b7-e6487a4e70ab', 'ADA-USD', 'CRYPTO', 'Cardano / US Dollar', 'CRYPTO', '0.0001', '0.000001', '0.000001', 0, 'SEED'),
 ('a0876c84-b708-3dc2-9ffa-77ef6aa91ed7', 'LTC-USD', 'CRYPTO', 'Litecoin / US Dollar', 'CRYPTO', '0.01', '0.00000001', '0.00000001', 0, 'SEED'),
 ('9c1c7851-f956-3ba3-bf1f-0a0aa1bb8dc8', 'BCH-USD', 'CRYPTO', 'Bitcoin Cash / US Dollar', 'CRYPTO', '0.01', '0.00000001', '0.00000001', 0, 'SEED'),
 ('e4b53f89-ee22-3313-bce5-ac941e718843', 'LINK-USD', 'CRYPTO', 'Chainlink / US Dollar', 'CRYPTO', '0.001', '0.000001', '0.000001', 0, 'SEED'),
 ('8fb99a56-6c63-3ae0-9509-c1615c3b8f8a', 'AVAX-USD', 'CRYPTO', 'Avalanche / US Dollar', 'CRYPTO', '0.001', '0.000001', '0.000001', 0, 'SEED'),
 ('98ba5b06-9dfd-3f69-957a-1d816e6ec63a', 'DOT-USD', 'CRYPTO', 'Polkadot / US Dollar', 'CRYPTO', '0.001', '0.000001', '0.000001', 0, 'SEED');
INSERT INTO risk_profiles(id, scope, scope_id, limits, created_at, updated_at) VALUES ('00000000-0000-0000-0000-00000000a001', 'GLOBAL', NULL, '{"maxTradePercentOfEquity":"20","maxInstrumentAllocationPercent":"25","maxAssetClassAllocationPercent":{"US_EQUITY":"100","CRYPTO":"50"},"maxOpenPositions":25,"maxTradesPerMinute":5,"maxTradesPerHour":60,"maxTradesPerDay":200,"cooldownSeconds":0,"maxDailyLossPercent":"5","maxDrawdownPercent":"25","maxConsecutiveLosses":6,"maxShortExposurePercent":"50","shortingAllowed":true,"maxQuoteAgeSeconds":60,"maxSpreadPercent":"1.0","maxParticipationPercent":"10","maxPriceDeviationPercent":"15","maxConsecutiveErrors":3,"denySymbols":[]}', 0, 0);
INSERT INTO emergency_state(singleton, updated_at) VALUES (1, 0);
INSERT INTO ai_budget(singleton, monthly_cost_limit_usd, daily_request_limit, max_output_tokens, max_input_chars, updated_at) VALUES (1, '20', 40, 8000, 60000, 0);
