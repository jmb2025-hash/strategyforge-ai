-- WP2: instrument master, market data, FX, corporate actions, watchlists, alerts, replay clock.
-- Prices decimal(38,12), quantities/volumes decimal(38,18) (section 7 financial precision).

create table instruments (
  id                 uuid primary key default gen_random_uuid(),
  symbol             text not null unique,
  asset_class        text not null check (asset_class in ('US_EQUITY', 'CRYPTO')),
  name               text not null,
  exchange           text not null,
  currency           text not null default 'USD' check (currency = 'USD'),
  price_increment    numeric(38,12) not null,
  quantity_increment numeric(38,18) not null,
  min_quantity       numeric(38,18) not null,
  shortable          boolean not null default false,
  active             boolean not null default true,
  source             text not null check (source in ('SEED', 'PROVIDER_LOOKUP')),
  created_at         timestamptz not null default now(),
  version            bigint not null default 0,
  constraint instrument_symbol_format check (symbol ~ '^[A-Z][A-Z0-9.]{0,9}(-USD)?$')
);

-- Curated seed. Equities/ETFs are US-listed; crypto is the v1 allowlist of major USD pairs.
insert into instruments(symbol, asset_class, name, exchange, price_increment, quantity_increment, min_quantity, shortable, source) values
 ('AAPL','US_EQUITY','Apple Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('MSFT','US_EQUITY','Microsoft Corporation','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('NVDA','US_EQUITY','NVIDIA Corporation','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('AMZN','US_EQUITY','Amazon.com Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('GOOGL','US_EQUITY','Alphabet Inc. Class A','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('META','US_EQUITY','Meta Platforms Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('TSLA','US_EQUITY','Tesla Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('BRK.B','US_EQUITY','Berkshire Hathaway Inc. Class B','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('JPM','US_EQUITY','JPMorgan Chase & Co.','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('V','US_EQUITY','Visa Inc.','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('MA','US_EQUITY','Mastercard Inc.','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('UNH','US_EQUITY','UnitedHealth Group Inc.','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('XOM','US_EQUITY','Exxon Mobil Corporation','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('JNJ','US_EQUITY','Johnson & Johnson','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('PG','US_EQUITY','Procter & Gamble Co.','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('HD','US_EQUITY','Home Depot Inc.','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('COST','US_EQUITY','Costco Wholesale Corporation','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('AVGO','US_EQUITY','Broadcom Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('LLY','US_EQUITY','Eli Lilly and Company','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('WMT','US_EQUITY','Walmart Inc.','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('KO','US_EQUITY','Coca-Cola Company','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('PEP','US_EQUITY','PepsiCo Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('BAC','US_EQUITY','Bank of America Corporation','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('DIS','US_EQUITY','Walt Disney Company','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('NFLX','US_EQUITY','Netflix Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('AMD','US_EQUITY','Advanced Micro Devices Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('INTC','US_EQUITY','Intel Corporation','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('CSCO','US_EQUITY','Cisco Systems Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('ORCL','US_EQUITY','Oracle Corporation','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('CRM','US_EQUITY','Salesforce Inc.','NYSE',0.01,0.0001,0.0001,true,'SEED'),
 ('ADBE','US_EQUITY','Adobe Inc.','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('SPY','US_EQUITY','SPDR S&P 500 ETF Trust','NYSE ARCA',0.01,0.0001,0.0001,true,'SEED'),
 ('QQQ','US_EQUITY','Invesco QQQ Trust','NASDAQ',0.01,0.0001,0.0001,true,'SEED'),
 ('IWM','US_EQUITY','iShares Russell 2000 ETF','NYSE ARCA',0.01,0.0001,0.0001,true,'SEED'),
 ('DIA','US_EQUITY','SPDR Dow Jones Industrial Average ETF','NYSE ARCA',0.01,0.0001,0.0001,true,'SEED'),
 ('VTI','US_EQUITY','Vanguard Total Stock Market ETF','NYSE ARCA',0.01,0.0001,0.0001,true,'SEED'),
 ('BTC-USD','CRYPTO','Bitcoin / US Dollar','CRYPTO',0.01,0.00000001,0.00000001,false,'SEED'),
 ('ETH-USD','CRYPTO','Ether / US Dollar','CRYPTO',0.01,0.00000001,0.00000001,false,'SEED'),
 ('SOL-USD','CRYPTO','Solana / US Dollar','CRYPTO',0.001,0.00000001,0.00000001,false,'SEED'),
 ('XRP-USD','CRYPTO','XRP / US Dollar','CRYPTO',0.0001,0.000001,0.000001,false,'SEED'),
 ('ADA-USD','CRYPTO','Cardano / US Dollar','CRYPTO',0.0001,0.000001,0.000001,false,'SEED'),
 ('LTC-USD','CRYPTO','Litecoin / US Dollar','CRYPTO',0.01,0.00000001,0.00000001,false,'SEED'),
 ('BCH-USD','CRYPTO','Bitcoin Cash / US Dollar','CRYPTO',0.01,0.00000001,0.00000001,false,'SEED'),
 ('LINK-USD','CRYPTO','Chainlink / US Dollar','CRYPTO',0.001,0.000001,0.000001,false,'SEED'),
 ('AVAX-USD','CRYPTO','Avalanche / US Dollar','CRYPTO',0.001,0.000001,0.000001,false,'SEED'),
 ('DOT-USD','CRYPTO','Polkadot / US Dollar','CRYPTO',0.001,0.000001,0.000001,false,'SEED');

create table candles (
  instrument_id uuid not null references instruments(id),
  timeframe     text not null check (timeframe in ('1m', '5m', '15m', '1h', '4h', '1d')),
  open_time     timestamptz not null,
  open          numeric(38,12) not null check (open > 0),
  high          numeric(38,12) not null check (high > 0),
  low           numeric(38,12) not null check (low > 0),
  close         numeric(38,12) not null check (close > 0),
  volume        numeric(38,18) not null check (volume >= 0),
  provider      text not null,
  feed_type     text not null,
  received_at   timestamptz not null,
  primary key (instrument_id, timeframe, open_time),
  constraint candle_ohlc_consistent check (high >= low and high >= open and high >= close and low <= open and low <= close)
);

create table latest_quotes (
  instrument_id uuid primary key references instruments(id),
  bid           numeric(38,12),
  ask           numeric(38,12),
  last          numeric(38,12) not null check (last > 0),
  bid_size      numeric(38,18),
  ask_size      numeric(38,18),
  exchange_ts   timestamptz not null,
  received_at   timestamptz not null,
  provider      text not null,
  feed_type     text not null check (feed_type in ('REPLAY_SYNTHETIC', 'REALTIME', 'DELAYED', 'UNKNOWN')),
  constraint quote_bid_ask check (bid is null or ask is null or bid <= ask)
);

-- Immutable market snapshots referenced by signals, orders and risk evaluations (FR-061, FR-085).
create table market_snapshots (
  id                uuid primary key,
  instrument_id     uuid not null references instruments(id),
  captured_at       timestamptz not null,
  market_time       timestamptz not null,
  provider          text not null,
  feed_type         text not null,
  bid               numeric(38,12),
  ask               numeric(38,12),
  last              numeric(38,12),
  quote_ts          timestamptz,
  quote_age_seconds bigint,
  freshness         text not null,
  detail            jsonb not null default '{}'::jsonb
);
create trigger market_snapshots_append_only before update or delete on market_snapshots for each row execute function sf_prevent_mutation();

-- Per-instrument data-quality flags (out-of-order, malformed, gaps) that block evaluation until cleared.
create table market_data_status (
  instrument_id uuid not null references instruments(id),
  timeframe     text not null,
  status        text not null check (status in ('OK', 'OUT_OF_ORDER', 'MALFORMED', 'CLOCK_SKEW', 'PROVIDER_ERROR', 'UNSUPPORTED')),
  detail        text,
  updated_at    timestamptz not null,
  primary key (instrument_id, timeframe)
);

create table fx_rates (
  id          bigserial primary key,
  base        text not null,
  quote       text not null,
  rate        numeric(38,12) not null check (rate > 0),
  as_of       timestamptz not null,
  provider    text not null,
  received_at timestamptz not null,
  unique (base, quote, as_of, provider)
);

create table corporate_actions (
  id            uuid primary key default gen_random_uuid(),
  instrument_id uuid not null references instruments(id),
  action_type   text not null check (action_type in ('SPLIT', 'CASH_DIVIDEND')),
  ex_date       date not null,
  pay_date      date,
  ratio_new     numeric(38,18),
  ratio_old     numeric(38,18),
  cash_amount   numeric(38,12),
  provider      text not null,
  received_at   timestamptz not null,
  unique (instrument_id, action_type, ex_date),
  constraint split_ratio check (action_type <> 'SPLIT' or (ratio_new > 0 and ratio_old > 0)),
  constraint dividend_amount check (action_type <> 'CASH_DIVIDEND' or cash_amount > 0)
);

-- Coverage of corporate-action data per instrument; UNAVAILABLE forces Manual Review Required (FR-025).
create table corporate_action_coverage (
  instrument_id uuid primary key references instruments(id),
  status        text not null check (status in ('AVAILABLE', 'UNAVAILABLE')),
  covered_from  date,
  covered_to    date,
  provider      text not null,
  detail        text,
  updated_at    timestamptz not null
);

create table watchlists (
  id          uuid primary key,
  name        text not null,
  created_at  timestamptz not null,
  archived_at timestamptz,
  version     bigint not null default 0
);

create table watchlist_items (
  watchlist_id  uuid not null references watchlists(id),
  instrument_id uuid not null references instruments(id),
  added_at      timestamptz not null,
  primary key (watchlist_id, instrument_id)
);

create table price_alerts (
  id              uuid primary key,
  instrument_id   uuid not null references instruments(id),
  condition       text not null check (condition in ('ABOVE', 'BELOW')),
  threshold       numeric(38,12) not null check (threshold > 0),
  status          text not null check (status in ('ACTIVE', 'TRIGGERED', 'CANCELLED')),
  note            text,
  created_at      timestamptz not null,
  triggered_at    timestamptz,
  triggered_price numeric(38,12),
  version         bigint not null default 0
);

-- Deterministic replay clock (singleton) used when the active provider is REPLAY.
create table replay_state (
  singleton    boolean primary key default true check (singleton),
  market_time  timestamptz not null,
  updated_at   timestamptz not null
);
