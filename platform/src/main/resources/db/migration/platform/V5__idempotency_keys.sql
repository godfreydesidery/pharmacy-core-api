-- Idempotency keys for money-moving POSTs (IETF draft "The Idempotency-Key HTTP Header Field",
-- draft-ietf-httpapi-idempotency-key-header). One row per pharmacy and key: the first request
-- reserves it (IN_PROGRESS), and a 2xx outcome is kept (COMPLETED) so a retry with the same key and
-- body gets the same answer instead of moving the money twice. A failed outcome deletes the row, so
-- the client may try again. Rows expire after 24 hours and the nightly retention deletes them.
create table platform.idempotency_keys (
  tenant_uid      uuid not null,
  idempotency_key text not null check (length(idempotency_key) between 1 and 255),
  request_method  text not null,
  request_path    text not null,
  -- SHA-256 of the request body, hex.
  fingerprint     text not null,
  user_uid        uuid,
  state           text not null check (state in ('IN_PROGRESS', 'COMPLETED')),
  response_status integer,
  response_type   text,
  response_body   text,
  created_at      timestamptz not null,
  completed_at    timestamptz,
  expires_at      timestamptz not null,
  primary key (tenant_uid, idempotency_key)
);
create index idempotency_keys_by_expiry on platform.idempotency_keys (expires_at);
alter table platform.idempotency_keys enable row level security;
alter table platform.idempotency_keys force row level security;
create policy tenant_isolation on platform.idempotency_keys
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update, delete on platform.idempotency_keys to ${application_role};

-- The nightly retention (platform V4) also deletes expired idempotency keys, per pharmacy under its
-- row-level security. Same guards as before: it runs as the owner (security definer) with a fixed
-- search path, and only the application role may call it.
create or replace function platform.purge_expired(p_now timestamptz, p_days jsonb, p_batch int) returns jsonb
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
    'notifications.inbox', 0, 'integrations.deliveries', 0, 'platform.idempotency_keys', 0);
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

      -- Idempotency keys past their 24 hours: a retry after that is a new request.
      delete from platform.idempotency_keys where ctid = any(array(
        select ctid from platform.idempotency_keys where expires_at < p_now limit p_batch));
      get diagnostics n = row_count;
      counts := jsonb_set(counts, '{platform.idempotency_keys}', to_jsonb((counts->>'platform.idempotency_keys')::bigint + n));
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
