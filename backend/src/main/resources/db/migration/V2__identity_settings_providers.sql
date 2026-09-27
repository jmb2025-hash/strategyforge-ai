-- WP1: identity, sessions, devices, owner settings, provider configuration.

-- Exactly one owner can ever exist (FR-001): the singleton primary key admits one row.
create table owners (
  singleton          boolean primary key default true check (singleton),
  id                 uuid not null unique,
  username           text not null,
  password_hash      text not null,
  totp_secret_enc    bytea,
  totp_pending_enc   bytea,
  totp_enabled       boolean not null default false,
  totp_last_step     bigint,
  failed_logins      int not null default 0,
  locked_until       timestamptz,
  created_at         timestamptz not null,
  password_changed_at timestamptz not null,
  version            bigint not null default 0
);

create table recovery_codes (
  id          uuid primary key,
  code_hash   text not null unique,
  generation  int not null,
  created_at  timestamptz not null,
  used_at     timestamptz
);

create table devices (
  id                    uuid primary key,
  name                  text not null,
  platform              text not null check (platform in ('ANDROID', 'OTHER')),
  push_token_enc        bytea,
  push_token_fingerprint text,
  push_enabled          boolean not null default false,
  created_at            timestamptz not null,
  last_seen_at          timestamptz not null,
  revoked_at            timestamptz
);

create table sessions (
  id                     uuid primary key,
  token_hash             text not null unique,
  device_id              uuid references devices(id),
  device_name            text not null,
  user_agent             text,
  created_at             timestamptz not null,
  last_seen_at           timestamptz not null,
  last_authenticated_at  timestamptz not null,
  expires_at             timestamptz not null,
  revoked_at             timestamptz,
  revoke_reason          text
);
create index sessions_active_idx on sessions (expires_at) where revoked_at is null;

-- Single-use server-side action tokens (section 14): stored hashed, bound to purpose/entity/session.
create table action_tokens (
  id          uuid primary key,
  token_hash  text not null unique,
  purpose     text not null,
  entity_id   text not null,
  session_id  uuid not null references sessions(id),
  created_at  timestamptz not null,
  expires_at  timestamptz not null,
  used_at     timestamptz
);

create table owner_settings (
  singleton            boolean primary key default true check (singleton),
  timezone             text not null,
  display_currency     text not null check (display_currency in ('USD', 'CAD')),
  show_cad_equivalent  boolean not null default false,
  theme                text not null check (theme in ('SYSTEM', 'LIGHT', 'DARK')),
  notifications        jsonb not null,
  privacy              jsonb not null,
  portfolio_defaults   jsonb not null,
  updated_at           timestamptz not null,
  version              bigint not null default 0
);

create table provider_configurations (
  id                     uuid primary key,
  kind                   text not null check (kind in ('MARKET_DATA', 'AI', 'PUSH')),
  provider_type          text not null,
  display_name           text not null,
  settings               jsonb not null default '{}'::jsonb,
  credential_enc         bytea,
  credential_key_id      text,
  credential_fingerprint text,
  credential_updated_at  timestamptz,
  active                 boolean not null default false,
  archived_at            timestamptz,
  last_test_at           timestamptz,
  last_test_status       text,
  last_test_detail       text,
  created_at             timestamptz not null,
  updated_at             timestamptz not null,
  version                bigint not null default 0,
  -- Real-money boundary: no provider kind can route orders (FR-113).
  constraint provider_type_allowed check (provider_type in ('REPLAY', 'TWELVE_DATA', 'ANTHROPIC', 'OPENAI', 'GEMINI', 'OPENROUTER', 'FCM'))
);
-- At most one active market-data provider.
create unique index provider_one_active_market on provider_configurations (kind) where active and kind = 'MARKET_DATA' and archived_at is null;

create table provider_capabilities (
  provider_id  uuid not null references provider_configurations(id),
  capability   text not null,
  status       text not null check (status in ('SUPPORTED', 'UNSUPPORTED', 'UNVERIFIED', 'DEGRADED')),
  detail       text,
  verified_at  timestamptz not null,
  primary key (provider_id, capability)
);
