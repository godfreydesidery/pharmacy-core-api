-- notifications: the outbox of messages (email, SMS, WhatsApp, in-app), the in-app inbox, tenant
-- template overrides and per-user preferences.
--
-- Messages and inbox items belong to a tenant, or to the platform when tenant_uid is null (such as
-- a root administrator's password reset). The policy shows a platform row only to a session with
-- no tenant, and a tenant row only to that tenant.

grant usage on schema notifications to ${application_role};

create table notifications.messages (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  tenant_uid         uuid,
  template_key       text not null,
  category           text not null,
  channel            text not null check (channel in ('EMAIL', 'SMS', 'WHATSAPP', 'IN_APP')),
  recipient_user_uid uuid,
  recipient_name     text,
  to_address         text not null,
  locale             text not null,
  subject            text,
  body               text not null,
  html_body          text,
  status             text not null check (status in ('QUEUED', 'SENDING', 'SENT', 'FAILED')),
  provider           text,
  attempts           integer not null default 0,
  next_attempt_at    timestamptz,
  last_error         text,
  sent_at            timestamptz,
  dedup_key          text,
  created_at         timestamptz not null,
  created_by         text not null,
  updated_at         timestamptz,
  updated_by         text,
  version            bigint not null default 0
);
create index messages_due on notifications.messages (next_attempt_at)
  where status in ('QUEUED', 'SENDING', 'FAILED');
create index messages_by_dedup on notifications.messages (dedup_key, channel, to_address, created_at)
  where dedup_key is not null;
create index messages_by_tenant_uid on notifications.messages (tenant_uid, uid);
alter table notifications.messages enable row level security;
alter table notifications.messages force row level security;
create policy scope_isolation on notifications.messages
  using (tenant_uid = platform.current_tenant_uid()
         or (tenant_uid is null and platform.current_tenant_uid() is null))
  with check (tenant_uid = platform.current_tenant_uid()
              or (tenant_uid is null and platform.current_tenant_uid() is null));
grant select, insert, update on notifications.messages to ${application_role};

create table notifications.inbox (
  id         bigint generated always as identity primary key,
  uid        uuid not null unique,
  tenant_uid uuid,
  user_uid   uuid not null,
  title      text not null,
  body       text not null,
  link       text,
  severity   text not null check (severity in ('info', 'warning', 'critical')),
  read_at    timestamptz,
  created_at timestamptz not null,
  created_by text not null,
  updated_at timestamptz,
  updated_by text,
  version    bigint not null default 0
);
create index inbox_by_user on notifications.inbox (user_uid, uid);
create index inbox_unread on notifications.inbox (user_uid) where read_at is null;
alter table notifications.inbox enable row level security;
alter table notifications.inbox force row level security;
create policy scope_isolation on notifications.inbox
  using (tenant_uid = platform.current_tenant_uid()
         or (tenant_uid is null and platform.current_tenant_uid() is null))
  with check (tenant_uid = platform.current_tenant_uid()
              or (tenant_uid is null and platform.current_tenant_uid() is null));
grant select, insert, update on notifications.inbox to ${application_role};

-- A tenant's own wording for one template key, channel and locale.
create table notifications.templates (
  id           bigint generated always as identity primary key,
  uid          uuid not null unique,
  tenant_uid   uuid not null,
  template_key text not null,
  channel      text not null check (channel in ('EMAIL', 'SMS', 'WHATSAPP', 'IN_APP')),
  locale       text not null,
  subject      text,
  body         text not null,
  created_at   timestamptz not null,
  created_by   text not null,
  updated_at   timestamptz,
  updated_by   text,
  version      bigint not null default 0,
  unique (tenant_uid, template_key, channel, locale)
);
alter table notifications.templates enable row level security;
alter table notifications.templates force row level security;
create policy tenant_isolation on notifications.templates
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on notifications.templates to ${application_role};

-- The channels a user chose for one category; no row means every channel.
create table notifications.preferences (
  id         bigint generated always as identity primary key,
  uid        uuid not null unique,
  tenant_uid uuid not null,
  user_uid   uuid not null,
  category   text not null,
  channels   text not null,
  created_at timestamptz not null,
  created_by text not null,
  updated_at timestamptz,
  updated_by text,
  version    bigint not null default 0,
  unique (tenant_uid, user_uid, category)
);
alter table notifications.preferences enable row level security;
alter table notifications.preferences force row level security;
create policy tenant_isolation on notifications.preferences
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on notifications.preferences to ${application_role};

-- The scopes (a tenant's uid, or the zero uid for the platform) with messages due for a retry, and
-- when the earliest is due. It holds no message content, only which scopes the retry job visits,
-- so it carries no row-level security: the job reads it before it knows which tenant to enter.
create table notifications.pending_scopes (
  scope_uid       uuid primary key,
  next_attempt_at timestamptz not null
);
grant select, insert, update, delete on notifications.pending_scopes to ${application_role};
