-- Monthly partitions counted from a given day rather than from the database's own date. Entries are
-- stamped with the application's clock, which in a simulated world runs ahead of the database's
-- now(); without a partition for that month they would land in the default partition, which is
-- append-only and can then never be split into its month. The one-argument form stays as it was.

create function audit.ensure_partitions(from_day date, months_ahead int) returns void
  language plpgsql
  security definer
  set search_path = audit, pg_temp
as $$
declare
  month_start date := date_trunc('month', coalesce(from_day, (now() at time zone 'utc')::date))::date;
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
end
$$;

revoke all on function audit.ensure_partitions(date, int) from public;
grant execute on function audit.ensure_partitions(date, int) to ${application_role};
