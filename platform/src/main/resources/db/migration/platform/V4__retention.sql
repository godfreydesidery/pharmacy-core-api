-- Retention: short-lived rows are deleted once they are no longer needed (Tanzania's Personal Data
-- Protection Act 2022, storage limitation). The application role still has no delete grant on
-- these tables; it may only call these functions, which run as their owner. Each call deletes at
-- most p_batch rows per table (per pharmacy, where row-level security applies), so the caller
-- repeats it in short transactions until nothing is left.
--
-- p_days holds the periods in days, counted back from p_now:
--   sessions     expired or revoked sign-in sessions (and the pharmacies entered in them)
--   codes        expired password-reset links, phone codes, email changes, sign-in challenges,
--                passkey ceremonies
--   invitations  expired, accepted or revoked invitations
--   messages     email/SMS/WhatsApp log entries already sent or given up on
--   inbox        in-app notifications already read
--   deliveries   webhook deliveries already delivered or given up on
--   events       internal event records already completed (open ones are never touched)
create function platform.purge_expired(p_now timestamptz, p_days jsonb, p_batch int) returns jsonb
  language plpgsql
  security definer
  set search_path = pg_catalog, pg_temp
as $$
declare
  counts jsonb := '{}';
  n      bigint;
  total  bigint;
  t      uuid;
  cut    timestamptz;

begin
  -- Sign-in data: no row-level security on these tables.
  cut := p_now - make_interval(days => (p_days->>'sessions')::int);
  with doomed as (
    select uid from access.sessions where expires_at < cut or revoked_at < cut limit p_batch
  ), entered as (
    delete from access.session_pharmacies where session_uid in (select uid from doomed)
  ), gone as (
    delete from access.sessions where uid in (select uid from doomed) returning 1
  )
  select count(*) into n from gone;
  counts := counts || jsonb_build_object('access.sessions', n);

  cut := p_now - make_interval(days => (p_days->>'codes')::int);
  delete from access.password_resets where ctid = any(array(
    select ctid from access.password_resets where expires_at < cut limit p_batch));
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('access.password_resets', n);
  delete from access.phone_codes where ctid = any(array(
    select ctid from access.phone_codes where expires_at < cut limit p_batch));
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('access.phone_codes', n);
  delete from access.email_changes where ctid = any(array(
    select ctid from access.email_changes where expires_at < cut limit p_batch));
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('access.email_changes', n);
  delete from access.sign_in_challenges where ctid = any(array(
    select ctid from access.sign_in_challenges where expires_at < cut limit p_batch));
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('access.sign_in_challenges', n);
  delete from access.webauthn_ceremonies where ctid = any(array(
    select ctid from access.webauthn_ceremonies where expires_at < cut limit p_batch));
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('access.webauthn_ceremonies', n);

  -- Completed internal events (the event log): an open one is still to be delivered.
  cut := p_now - make_interval(days => (p_days->>'events')::int);
  delete from platform.event_publication where ctid = any(array(
    select ctid from platform.event_publication where completion_date < cut limit p_batch));
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('platform.event_publication', n);

  -- Each pharmacy's own rows, under its row-level security, then the platform's (no pharmacy).
  counts := counts || jsonb_build_object('access.invitations', 0, 'notifications.messages', 0,
    'notifications.inbox', 0, 'integrations.deliveries', 0);
  for t in select uid from tenancy.tenants union all select null loop
    perform set_config('pharmacy.tenant_uid', coalesce(t::text, ''), true);
    if t is not null then
      cut := p_now - make_interval(days => (p_days->>'invitations')::int);
      delete from access.invitations where ctid = any(array(
        select ctid from access.invitations
         where expires_at < cut or accepted_at < cut or revoked_at < cut limit p_batch));
      get diagnostics n = row_count;
      counts := jsonb_set(counts, '{access.invitations}', to_jsonb((counts->>'access.invitations')::bigint + n));

      cut := p_now - make_interval(days => (p_days->>'deliveries')::int);
      delete from integrations.deliveries where ctid = any(array(
        select ctid from integrations.deliveries
         where created_at < cut and status in ('DELIVERED', 'FAILED') limit p_batch));
      get diagnostics n = row_count;
      counts := jsonb_set(counts, '{integrations.deliveries}', to_jsonb((counts->>'integrations.deliveries')::bigint + n));
    end if;

    cut := p_now - make_interval(days => (p_days->>'messages')::int);
    delete from notifications.messages where ctid = any(array(
      select ctid from notifications.messages
       where created_at < cut and status in ('SENT', 'FAILED') limit p_batch));
    get diagnostics n = row_count;
    counts := jsonb_set(counts, '{notifications.messages}', to_jsonb((counts->>'notifications.messages')::bigint + n));

    cut := p_now - make_interval(days => (p_days->>'inbox')::int);
    delete from notifications.inbox where ctid = any(array(
      select ctid from notifications.inbox where read_at < cut limit p_batch));
    get diagnostics n = row_count;
    counts := jsonb_set(counts, '{notifications.inbox}', to_jsonb((counts->>'notifications.inbox')::bigint + n));
  end loop;
  perform set_config('pharmacy.tenant_uid', '', true);

  select coalesce(sum(value::bigint), 0) into total from jsonb_each_text(counts);
  return counts || jsonb_build_object('total', total);
end
$$;

revoke all on function platform.purge_expired(timestamptz, jsonb, int) from public;
grant execute on function platform.purge_expired(timestamptz, jsonb, int) to ${application_role};
