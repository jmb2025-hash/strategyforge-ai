-- Authoritative in-app notification inbox (FR-100) and push delivery outbox (FR-101).

create table notification_events (
  id               uuid primary key,
  category         text not null,
  severity         text not null check (severity in ('INFO', 'WARNING', 'CRITICAL')),
  title            text not null,
  body             text not null,
  -- Lock-screen/push text is always redacted: no symbols, amounts or strategy names (FR-102).
  redacted_title   text not null,
  redacted_body    text not null,
  entity_type      text,
  entity_id        text,
  deep_link        text,
  dedupe_key       text unique,
  push_status      text not null check (push_status in ('NO_CHANNEL', 'DISABLED_BY_PREFERENCE', 'QUEUED', 'SENT', 'PARTIAL', 'FAILED')),
  created_at       timestamptz not null,
  read_at          timestamptz
);
create index notification_events_created_idx on notification_events (created_at desc, id desc);
create index notification_events_unread_idx on notification_events (created_at desc) where read_at is null;

create table push_deliveries (
  id              uuid primary key,
  notification_id uuid not null references notification_events(id),
  device_id       uuid not null references devices(id),
  status          text not null check (status in ('PENDING', 'SENT', 'FAILED')),
  attempts        int not null default 0,
  last_error      text,
  created_at      timestamptz not null,
  updated_at      timestamptz not null,
  next_attempt_at timestamptz not null,
  unique (notification_id, device_id)
);
create index push_deliveries_pending_idx on push_deliveries (next_attempt_at) where status = 'PENDING';
