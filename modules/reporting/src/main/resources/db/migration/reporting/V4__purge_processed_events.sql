-- The purge of reporting.processed_events (adversarial review PERF-13). A marker only makes a
-- redelivered event a no-op, and an event is redelivered within minutes (event resubmission every 5
-- minutes; completed publications themselves go after 7 days), so markers older than the retention
-- period (pharmacy.retention.processed-events-days, 30 by default) are deleted every night by the
-- reporting module's retention task, a batch at a time, through the function below.
--
-- The function runs as the owner of this schema, which may read and delete these markers of every
-- pharmacy (the two policies below, for the owner only); the application role still sees only its
-- current pharmacy's, and may not delete them itself. The cut-off is counted from the database's
-- now() at the latest, and is never less than one day.

do $$
begin
  execute format('create policy purge_read_across_pharmacies on reporting.processed_events'
                 ' for select to %I using (true)', current_user);
  execute format('create policy purge_across_pharmacies on reporting.processed_events'
                 ' for delete to %I using (true)', current_user);
end
$$;

create function reporting.purge_processed_events(p_now timestamptz, p_days int, p_batch int)
  returns bigint
  language plpgsql
  security definer
  set search_path = pg_catalog, pg_temp
as $$
declare
  v_cut timestamptz := least(p_now, now()) - make_interval(days => greatest(coalesce(p_days, 30), 1));
  n     bigint;
begin
  delete from reporting.processed_events where ctid = any(array(
    select ctid from reporting.processed_events
     where processed_at < v_cut
     limit greatest(coalesce(p_batch, 5000), 1)));
  get diagnostics n = row_count;
  return n;
end
$$;

revoke all on function reporting.purge_processed_events(timestamptz, int, int) from public;
grant execute on function reporting.purge_processed_events(timestamptz, int, int) to ${application_role};
