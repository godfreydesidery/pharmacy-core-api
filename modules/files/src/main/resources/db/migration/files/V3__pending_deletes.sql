-- Objects the store could not delete when asked (an erased pharmacy's files, an expired export), to
-- be tried again by the nightly retention instead of being forgotten in a log line (adversarial
-- review of 23 Nov 2026, INT-16). Platform scope: a key, not a pharmacy's record, so no tenant_uid
-- and no row-level security (the key names the pharmacy's folder, which may already be erased).
-- A retry claims its rows with FOR UPDATE SKIP LOCKED, so two instances never try the same key.
create table files.pending_deletes (
  id              bigint generated always as identity primary key,
  storage_key     text not null unique,
  reason          text not null,
  attempts        int not null default 0,
  last_error      text,
  created_at      timestamptz not null default now(),
  next_attempt_at timestamptz not null default now()
);
create index pending_deletes_due on files.pending_deletes (next_attempt_at);
grant select, insert, update, delete on files.pending_deletes to ${application_role};
