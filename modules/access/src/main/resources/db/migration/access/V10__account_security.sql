-- Account security owned by the person (gaps G3, G5, G9, G13): a verified phone, email changes
-- confirmed from the new mailbox, codes sent by SMS, and recovery assisted by the platform.
-- These tables are platform-scope like password_resets: no tenant, only the access module reads
-- them.

-- A phone is verified when its holder typed the code sent to it. Only verified phones can recover
-- an account, and each belongs to one account at a time: Tanzanian numbers are recycled, so the
-- latest person to verify a number takes it and the earlier account loses it (and is told).
alter table access.users add column phone_verified_at timestamptz;
create unique index users_verified_phone on access.users (phone) where phone_verified_at is not null;

-- Short codes sent by SMS: to verify a phone (VERIFY) or to reset a password (RESET). Only the
-- code's hash is kept; a code dies after its expiry, its first use or five wrong tries.
create table access.phone_codes (
  id             bigint generated always as identity primary key,
  uid            uuid not null unique,
  user_uid       uuid not null references access.users (uid),
  purpose        text not null check (purpose in ('VERIFY', 'RESET')),
  phone          text not null,
  code_hash      text not null,
  expires_at     timestamptz not null,
  attempts       int not null default 0,
  used_at        timestamptz,
  requested_ip   text,
  created_at     timestamptz not null,
  created_by     text not null,
  created_by_uid uuid,
  updated_at     timestamptz,
  updated_by     text,
  updated_by_uid uuid,
  version        bigint not null default 0
);
create index phone_codes_by_user on access.phone_codes (user_uid, purpose, created_at desc);
grant select, insert, update on access.phone_codes to ${application_role};

-- A change of sign-in email waiting for the new mailbox to confirm it. CHANGE is asked for by the
-- person (password re-entered); RECOVERY by a root admin for a person who lost their mailbox, with
-- a hold (not_before) during which the person's old email and phone are told and may cancel it.
create table access.email_changes (
  id                bigint generated always as identity primary key,
  uid               uuid not null unique,
  user_uid          uuid not null references access.users (uid),
  kind              text not null check (kind in ('CHANGE', 'RECOVERY')),
  old_email         text not null,
  new_email         text not null check (new_email = lower(new_email)),
  token_hash        text not null unique,
  cancel_token_hash text unique,
  not_before        timestamptz,
  expires_at        timestamptz not null,
  used_at           timestamptz,
  cancelled_at      timestamptz,
  reset_two_factor  boolean not null default false,
  reason            text,
  requested_ip      text,
  created_at        timestamptz not null,
  created_by        text not null,
  created_by_uid    uuid,
  updated_at        timestamptz,
  updated_by        text,
  updated_by_uid    uuid,
  version           bigint not null default 0
);
create index email_changes_by_user on access.email_changes (user_uid, created_at desc);
grant select, insert, update on access.email_changes to ${application_role};
