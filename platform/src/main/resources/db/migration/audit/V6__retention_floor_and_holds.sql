-- The audit partition drop, hardened (adversarial review of 23 Nov 2026: INT-8, INT-10, ISO-5).
--
-- 1. A floor: no month younger than audit.minimum_retention() is ever dropped, whatever date the
--    caller gives (drop_partitions_before('2100-01-01') used to wipe the whole trail). The cut-off is
--    counted from the database's now(), never from a date in the future.
-- 2. Legal holds: a month is kept while any pharmacy under a legal hold has entries in it. The
--    partitions are monthly across all pharmacies, so the choice was to keep the month whole or to
--    copy the held pharmacy's rows out first. Keeping it is the simplest correct way: nothing of the
--    held pharmacy moves or changes (its trail stays where its audit screens and the erasure look),
--    and the month is dropped on the first night after the last hold on it is lifted. The cost is
--    that other pharmacies' entries of that month outlive their period while the hold lasts.
--
-- No existing row is changed by this migration.

-- The audit trail's minimum retention: no month younger than this is dropped (the named constant).
create function audit.minimum_retention() returns interval
  language sql
  immutable
as $$ select interval '2 years' $$;

create or replace function audit.drop_partitions_before(p_before date) returns int
  language plpgsql
  security definer
  set search_path = audit, pg_temp
as $$
declare
  part      text;
  first_day date;
  next_day  date;
  held      uuid;
  kept      boolean;
  dropped   int := 0;
  previous  text := current_setting('pharmacy.tenant_uid', true);
  v_before  date := least(p_before,
                          ((now() at time zone 'utc') - audit.minimum_retention())::date);
begin
  for part in
    select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid
     where i.inhparent = 'audit.entries'::regclass and c.relname ~ '^entries_[0-9]{4}_[0-9]{2}$'
     order by c.relname
  loop
    first_day := to_date(substring(part from 9), 'YYYY_MM');
    next_day := (first_day + interval '1 month')::date;
    if next_day <= v_before then
      -- Row-level security is forced on the owner too: look as each held pharmacy in turn.
      kept := false;
      for held in select uid from tenancy.tenants where legal_hold loop
        perform set_config('pharmacy.tenant_uid', held::text, true);
        if exists (select 1 from audit.entries e
                    where e.tenant_uid = held
                      and e.at >= first_day::timestamp at time zone 'utc'
                      and e.at < next_day::timestamp at time zone 'utc') then
          kept := true;
          exit;
        end if;
      end loop;
      perform set_config('pharmacy.tenant_uid', coalesce(previous, ''), true);
      if not kept then
        execute format('drop table audit.%I', part);
        dropped := dropped + 1;
      end if;
    end if;
  end loop;
  return dropped;
end
$$;

revoke all on function audit.drop_partitions_before(date) from public;
grant execute on function audit.drop_partitions_before(date) to ${application_role};
