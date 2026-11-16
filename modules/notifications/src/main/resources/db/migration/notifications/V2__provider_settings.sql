-- Message provider settings per channel: the platform's default (tenant_uid null, set by a root
-- administrator) and a pharmacy's own account (its tenant row, mode OWN), or its choice to use the
-- platform's (mode PLATFORM). Secrets are sealed with AES-GCM by the application; the database
-- never holds them in clear. Environment variables stay the fallback when no row says otherwise.
--
-- A tenant session may read the platform row (to send through it) but write only its own; a
-- platform session (no tenant) reads and writes the platform row.

create table notifications.provider_settings (
  id          bigint generated always as identity primary key,
  uid         uuid not null unique,
  tenant_uid  uuid,
  channel     text not null check (channel in ('EMAIL', 'SMS', 'WHATSAPP')),
  mode        text not null check (mode in ('PLATFORM', 'OWN')),
  provider    text not null,
  settings    text not null default '{}',
  secrets     text,
  created_at  timestamptz not null,
  created_by  text not null,
  updated_at  timestamptz,
  updated_by  text,
  version     bigint not null default 0
);
create unique index provider_settings_unique
  on notifications.provider_settings (coalesce(tenant_uid, '00000000-0000-0000-0000-000000000000'), channel);
alter table notifications.provider_settings enable row level security;
alter table notifications.provider_settings force row level security;
create policy read_own_and_platform on notifications.provider_settings
  for select
  using (tenant_uid is null or tenant_uid = platform.current_tenant_uid());
create policy write_own_scope on notifications.provider_settings
  for all
  using (tenant_uid = platform.current_tenant_uid()
         or (tenant_uid is null and platform.current_tenant_uid() is null))
  with check (tenant_uid = platform.current_tenant_uid()
              or (tenant_uid is null and platform.current_tenant_uid() is null));
grant select, insert, update on notifications.provider_settings to ${application_role};
