-- The audit trail is kept for its retention period (7 years by default) in monthly partitions;
-- a month wholly before p_before is dropped as one piece. The default partition is never dropped.
create function audit.drop_partitions_before(p_before date) returns int
  language plpgsql
  security definer
  set search_path = audit, pg_temp
as $$
declare
  part    text;
  dropped int := 0;
begin
  for part in
    select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid
     where i.inhparent = 'audit.entries'::regclass and c.relname ~ '^entries_[0-9]{4}_[0-9]{2}$'
  loop
    if (to_date(substring(part from 9), 'YYYY_MM') + interval '1 month')::date <= p_before then
      execute format('drop table audit.%I', part);
      dropped := dropped + 1;
    end if;
  end loop;
  return dropped;
end
$$;

revoke all on function audit.drop_partitions_before(date) from public;
grant execute on function audit.drop_partitions_before(date) to ${application_role};
