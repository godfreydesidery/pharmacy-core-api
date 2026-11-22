-- Several providers per channel, tried in order (failover): each scope (the platform, or a
-- pharmacy) keeps a list per channel. A message goes through the first provider that accepts it;
-- one that keeps failing is tried last for a while (circuit breaker, in memory).
--
-- A pharmacy's row in mode PLATFORM meant "use the platform's providers": it becomes an inactive
-- provider of its own, kept for later. A pharmacy uses its own list when it has an active one.
drop index notifications.provider_settings_unique;

alter table notifications.provider_settings
  add column name text,
  add column priority int not null default 1,
  add column active boolean not null default true,
  add column last_sent_at timestamptz,
  add column last_failed_at timestamptz,
  add column last_error text;

-- Every row, every pharmacy's too: row-level security is forced even on the owner, so it is lifted
-- for the backfill and put back (as platform.add_actor_uids does).
alter table notifications.provider_settings no force row level security;
update notifications.provider_settings set name = provider;
update notifications.provider_settings set active = false, mode = 'OWN' where mode = 'PLATFORM';
alter table notifications.provider_settings force row level security;
alter table notifications.provider_settings alter column name set not null;

create index provider_settings_chain
  on notifications.provider_settings (tenant_uid, channel, priority, id);

grant delete on notifications.provider_settings to ${application_role};
