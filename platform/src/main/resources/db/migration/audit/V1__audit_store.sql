-- audit: the append-only trail of every change (before and after) and of security events.
--
-- Partitioned by month on "at", so old months can be detached and archived cheaply. A row belongs
-- to a tenant, or to the platform when tenant_uid is null: a tenant sees only its own rows, and
-- platform rows are visible only to a transaction that names no tenant (the root administrator's
-- platform routes).

grant usage on schema audit to ${application_role};

create table audit.entries (
  uid            uuid        not null,
  at             timestamptz not null,
  tenant_uid     uuid,
  branch_uid     uuid,
  actor          text        not null,
  action         text        not null,
  entity_type    text,
  entity_uid     uuid,
  before         jsonb,
  after          jsonb,
  correlation_id text,
  primary key (uid, at)
) partition by range (at);

create index entries_by_tenant_uid on audit.entries (tenant_uid, uid desc);
create index entries_by_entity on audit.entries (entity_type, entity_uid);
create index entries_by_actor on audit.entries (actor);

alter table audit.entries enable row level security;
alter table audit.entries force row level security;
create policy tenant_or_platform on audit.entries
  using ((tenant_uid is null and platform.current_tenant_uid() is null)
         or tenant_uid = platform.current_tenant_uid())
  with check ((tenant_uid is null and platform.current_tenant_uid() is null)
              or tenant_uid = platform.current_tenant_uid());

-- Append-only: no update or delete grant, and a trigger as the second line of defence.
create trigger entries_are_append_only
  before update or delete on audit.entries
  for each row execute function platform.refuse_change();

grant select, insert on audit.entries to ${application_role};

-- Creates the monthly partitions from this month to months_ahead months ahead, and the default
-- partition, each with row-level security forced (nobody queries a partition directly; the
-- parent's policy governs every read and write through it). It runs as the owner of the schema,
-- so the application role can call it without the right to create tables.
create function audit.ensure_partitions(months_ahead int) returns void
  language plpgsql
  security definer
  set search_path = audit, pg_temp
as $$
declare
  month_start date := date_trunc('month', now() at time zone 'utc')::date;
  first_day   date;
  next_day    date;
  partition   text;
begin
  for i in 0 .. greatest(months_ahead, 0) loop
    first_day := (month_start + make_interval(months => i))::date;
    next_day  := (first_day + interval '1 month')::date;
    partition := format('entries_%s', to_char(first_day, 'YYYY_MM'));
    if to_regclass('audit.' || partition) is null then
      execute format(
        'create table audit.%I partition of audit.entries for values from (%L) to (%L)',
        partition, first_day, next_day);
      execute format('alter table audit.%I enable row level security', partition);
      execute format('alter table audit.%I force row level security', partition);
    end if;
  end loop;
  if to_regclass('audit.entries_default') is null then
    create table audit.entries_default partition of audit.entries default;
    alter table audit.entries_default enable row level security;
    alter table audit.entries_default force row level security;
  end if;
end
$$;

revoke all on function audit.ensure_partitions(int) from public;
grant execute on function audit.ensure_partitions(int) to ${application_role};

select audit.ensure_partitions(2);
