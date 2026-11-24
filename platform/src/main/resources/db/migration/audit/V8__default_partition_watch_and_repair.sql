-- OPS-18 (adversarial review 23 Nov 2026): the default partition must not trap entries.
--
-- An entry whose month has no partition yet lands in audit.entries_default. Once it holds an entry
-- of a month, that month's partition can no longer be created (PostgreSQL refuses a partition the
-- default already has rows for), so if the nightly audit-partitions job keeps failing, every later
-- entry of that month piles up there for good. Now:
--   * audit.default_partition_rows() counts what the default partition holds; the nightly job
--     publishes it (pharmacy.audit.default_partition.rows) and logs AUDIT_DEFAULT_PARTITION_ROWS,
--     and the job run is marked failed, while it is above zero;
--   * audit.repair_default_partition() moves them into their monthly partitions (runbook
--     "Audit default partition"; the job does it itself when pharmacy.audit.repair-default-
--     partition is on). Nothing is lost or changed: the rows are copied whole, under each
--     pharmacy's own row-level security, and the count is checked before the old table goes.

-- The partitions force row-level security and have no policy, so not even their owner (who runs
-- these definer functions) reads one directly. The owner may read the default partition.
do $$
begin
  execute format(
    'create policy maintenance_reads on audit.entries_default for select using (current_user = %L)',
    current_user);
end
$$;

create function audit.default_partition_rows() returns bigint
  language sql
  stable
  security definer
  set search_path = audit, pg_temp
as $$
  select count(*) from audit.entries_default
$$;

revoke all on function audit.default_partition_rows() from public;
grant execute on function audit.default_partition_rows() to ${application_role};

-- Detaches the default partition, creates the partitions of the months its rows belong to and a
-- new empty default, copies the rows back through the parent (each lands in its month), checks
-- the count and drops the old table; returns how many rows moved (0 when there was nothing). One
-- transaction: on any error nothing has changed. It holds an exclusive lock on audit.entries for
-- its few moments (no entry is written meanwhile; writers wait).
create function audit.repair_default_partition() returns bigint
  language plpgsql
  security definer
  set search_path = audit, pg_temp
as $$
declare
  trapped bigint;
  moved   bigint := 0;
  n       bigint;
  m       date;
  t       uuid;
  columns text;
begin
  select count(*) into trapped from audit.entries_default;
  if trapped = 0 then
    return 0;
  end if;
  alter table audit.entries detach partition audit.entries_default;
  alter table audit.entries_default rename to entries_trapped;
  for m in select distinct date_trunc('month', at)::date from audit.entries_trapped loop
    perform audit.ensure_partitions(m, 0);
  end loop;
  create table audit.entries_default partition of audit.entries default;
  alter table audit.entries_default enable row level security;
  alter table audit.entries_default force row level security;
  execute format(
    'create policy maintenance_reads on audit.entries_default for select using (current_user = %L)',
    current_user);
  select string_agg(quote_ident(attname), ', ' order by attnum) into columns
    from pg_attribute
   where attrelid = 'audit.entries'::regclass and attnum > 0 and not attisdropped;
  -- Through the parent, which routes each row to its month and checks it against the pharmacy's
  -- policy: one pharmacy at a time (and the platform's own rows, with no tenant).
  for t in select distinct tenant_uid from audit.entries_trapped loop
    perform set_config('pharmacy.tenant_uid', coalesce(t::text, ''), true);
    execute format(
      'insert into audit.entries (%s) select %s from audit.entries_trapped'
        || ' where tenant_uid is not distinct from %L',
      columns, columns, t);
    get diagnostics n = row_count;
    moved := moved + n;
  end loop;
  perform set_config('pharmacy.tenant_uid', '', true);
  if moved <> trapped then
    raise exception 'audit repair copied % of % rows; nothing was changed', moved, trapped;
  end if;
  drop table audit.entries_trapped;
  return moved;
end
$$;

revoke all on function audit.repair_default_partition() from public;
grant execute on function audit.repair_default_partition() to ${application_role};
