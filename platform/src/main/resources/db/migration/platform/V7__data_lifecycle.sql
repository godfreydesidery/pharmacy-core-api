-- The data lifecycle, hardened (adversarial review of 23 Nov 2026: INT-7, INT-8, INT-10, ISO-5,
-- INT-16, PERF-12).
--
-- 1. A pharmacy's full export is a background job (platform.data_exports): the request records it,
--    a worker on any instance claims it (FOR UPDATE SKIP LOCKED), builds the ZIP in short
--    transactions and stores it in the file store, and the pharmacy downloads it through a signed
--    link. One export at a time per pharmacy (a unique index on the open ones).
-- 2. The definer functions never trust a date from their caller beyond the database's now(): the
--    retention cut-offs and the erasure's cooling-off are counted from now() at the latest.
-- 3. The nightly purge leaves a pharmacy under a legal hold alone.
-- 4. Erasure also deletes the pharmacy's internal event records (Spring Modulith's
--    platform.event_publication, which has no tenant_uid: the event's metadata names it), so an
--    undelivered event of an erased pharmacy is not replayed after a restart.
--
-- No existing row is changed by this migration.

-- ---------------------------------------------------------------------------------------- exports

-- One full export of a pharmacy. STORED exports are built by the worker and kept as a file for
-- keep-days; STREAMED ones are the old synchronous download, built and sent by the request itself
-- (they hold the pharmacy's one export slot while they run, and keep no file).
--   PENDING   recorded, waiting for a worker
--   RUNNING   a worker (or the request) is building it; heartbeat_at shows it is alive
--   READY     the ZIP is stored as file_uid until expires_at
--   DELIVERED a STREAMED export that was sent
--   FAILED    gave up (error says why)
--   EXPIRED   READY past expires_at: the file is deleted
create table platform.data_exports (
  id               bigint generated always as identity primary key,
  uid              uuid not null unique,
  tenant_uid       uuid not null,
  mode             text not null check (mode in ('STORED', 'STREAMED')),
  status           text not null
                   check (status in ('PENDING', 'RUNNING', 'READY', 'DELIVERED', 'FAILED', 'EXPIRED')),
  requested_at     timestamptz not null default now(),
  requested_by     text not null,
  requested_by_uid uuid,
  attempts         int not null default 0,
  started_at       timestamptz,
  heartbeat_at     timestamptz,
  finished_at      timestamptz,
  expires_at       timestamptz,
  file_uid         uuid,
  size_bytes       bigint,
  error            text
);
-- One export at a time per pharmacy: a second request while one is open fails on this index.
create unique index data_exports_one_open on platform.data_exports (tenant_uid)
  where status in ('PENDING', 'RUNNING');
create index data_exports_by_status on platform.data_exports (status, id);
alter table platform.data_exports enable row level security;
alter table platform.data_exports force row level security;
create policy tenant_isolation on platform.data_exports
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
-- The worker claims the next job of any pharmacy through platform.claim_data_export, which runs as
-- the owner of this schema: this policy lets the owner (and nobody else) see every pharmacy's jobs.
-- The application role still sees only its current pharmacy's.
do $$
begin
  execute format('create policy jobs_across_pharmacies on platform.data_exports to %I'
                 ' using (true) with check (true)', current_user);
end
$$;
grant select, insert, update on platform.data_exports to ${application_role};

-- The next export to build, claimed for the caller: the oldest PENDING one, or a RUNNING one whose
-- worker stopped sending heartbeats (its instance died) and which has attempts left. SKIP LOCKED:
-- two instances never claim the same job. A dead job out of attempts, and a dead STREAMED one (its
-- request is gone), are marked FAILED on the way, which frees the pharmacy's slot.
create function platform.claim_data_export(p_stale_after interval, p_max_attempts int)
  returns table (job_uid uuid, job_tenant_uid uuid, job_attempts int)
  language plpgsql
  security definer
  set search_path = pg_catalog, pg_temp
as $$
begin
  update platform.data_exports e
     set status = 'FAILED', finished_at = now(), expires_at = now(),
         error = case when e.mode = 'STREAMED' then 'the download stopped before it finished'
                      else 'gave up after ' || e.attempts || ' attempts' end
   where e.status = 'RUNNING'
     and e.heartbeat_at < now() - p_stale_after
     and (e.mode = 'STREAMED' or e.attempts >= p_max_attempts);

  return query
  with next_job as (
    select e.id from platform.data_exports e
     where e.mode = 'STORED'
       and (e.status = 'PENDING' or (e.status = 'RUNNING' and e.heartbeat_at < now() - p_stale_after))
     order by e.id
     limit 1
     for update skip locked
  )
  update platform.data_exports e
     set status = 'RUNNING', attempts = e.attempts + 1, started_at = now(), heartbeat_at = now(),
         error = null
    from next_job
   where e.id = next_job.id
  returning e.uid, e.tenant_uid, e.attempts;
end
$$;
revoke all on function platform.claim_data_export(interval, int) from public;
grant execute on function platform.claim_data_export(interval, int) to ${application_role};

-- READY exports past their expiry, of every pharmacy, for the retention job to delete their files.
create function platform.data_exports_to_expire(p_limit int)
  returns table (job_uid uuid, job_tenant_uid uuid, job_file_uid uuid)
  language plpgsql
  security definer
  set search_path = pg_catalog, pg_temp
as $$
begin
  return query
  select e.uid, e.tenant_uid, e.file_uid from platform.data_exports e
   where e.status = 'READY' and e.expires_at < now()
   order by e.id
   limit p_limit;
end
$$;
revoke all on function platform.data_exports_to_expire(int) from public;
grant execute on function platform.data_exports_to_expire(int) to ${application_role};

-- -------------------------------------------------------------------------------------- retention

-- A retention cut-off: p_days->>p_key days before p_now, where p_now is never later than the
-- database's now() and a negative period counts as none. A missing period gives null, which
-- matches no row (nothing is deleted).
create function platform.retention_cut(p_now timestamptz, p_days jsonb, p_key text)
  returns timestamptz
  language sql
  stable
  set search_path = pg_catalog, pg_temp
as $$
  select least(p_now, now())
         - make_interval(days => case when (p_days->>p_key)::int < 0 then 0
                                      else (p_days->>p_key)::int end)
$$;

-- The nightly purge, as in V5, with three changes: every cut-off is counted from now() at the
-- latest (platform.retention_cut), a pharmacy under a legal hold is skipped (its rows stay until
-- the hold is lifted), and finished export jobs go after p_days->>'exports' days.
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
  v_now  timestamptz := least(p_now, now());

begin
  -- Sign-in data: no row-level security on these tables.
  cut := platform.retention_cut(p_now, p_days, 'sessions');
  with doomed as (
    select uid from access.sessions where expires_at < cut or revoked_at < cut limit p_batch
  ), entered as (
    delete from access.session_pharmacies where session_uid in (select uid from doomed)
  ), gone as (
    delete from access.sessions where uid in (select uid from doomed) returning 1
  )
  select count(*) into n from gone;
  counts := counts || jsonb_build_object('access.sessions', n);

  cut := platform.retention_cut(p_now, p_days, 'codes');
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
  cut := platform.retention_cut(p_now, p_days, 'events');
  delete from platform.event_publication where ctid = any(array(
    select ctid from platform.event_publication where completion_date < cut limit p_batch));
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('platform.event_publication', n);

  -- Each pharmacy's own rows, under its row-level security, then the platform's (no pharmacy). A
  -- pharmacy under a legal hold keeps everything until the hold is lifted.
  counts := counts || jsonb_build_object('access.invitations', 0, 'notifications.messages', 0,
    'notifications.inbox', 0, 'integrations.deliveries', 0, 'platform.idempotency_keys', 0,
    'platform.data_exports', 0);
  for t in select uid from tenancy.tenants where not legal_hold union all select null loop
    perform set_config('pharmacy.tenant_uid', coalesce(t::text, ''), true);
    if t is not null then
      cut := platform.retention_cut(p_now, p_days, 'invitations');
      delete from access.invitations where ctid = any(array(
        select ctid from access.invitations
         where expires_at < cut or accepted_at < cut or revoked_at < cut limit p_batch));
      get diagnostics n = row_count;
      counts := jsonb_set(counts, '{access.invitations}', to_jsonb((counts->>'access.invitations')::bigint + n));

      cut := platform.retention_cut(p_now, p_days, 'deliveries');
      delete from integrations.deliveries where ctid = any(array(
        select ctid from integrations.deliveries
         where created_at < cut and status in ('DELIVERED', 'FAILED') limit p_batch));
      get diagnostics n = row_count;
      counts := jsonb_set(counts, '{integrations.deliveries}', to_jsonb((counts->>'integrations.deliveries')::bigint + n));

      -- Idempotency keys past their 24 hours: a retry after that is a new request.
      delete from platform.idempotency_keys where ctid = any(array(
        select ctid from platform.idempotency_keys where expires_at < v_now limit p_batch));
      get diagnostics n = row_count;
      counts := jsonb_set(counts, '{platform.idempotency_keys}', to_jsonb((counts->>'platform.idempotency_keys')::bigint + n));

      -- Finished export jobs (their files went when they expired).
      cut := platform.retention_cut(p_now, p_days, 'exports');
      delete from platform.data_exports where ctid = any(array(
        select ctid from platform.data_exports
         where status in ('EXPIRED', 'FAILED', 'DELIVERED')
           and coalesce(expires_at, finished_at, requested_at) < cut limit p_batch));
      get diagnostics n = row_count;
      counts := jsonb_set(counts, '{platform.data_exports}', to_jsonb((counts->>'platform.data_exports')::bigint + n));
    end if;

    cut := platform.retention_cut(p_now, p_days, 'messages');
    delete from notifications.messages where ctid = any(array(
      select ctid from notifications.messages
       where created_at < cut and status in ('SENT', 'FAILED') limit p_batch));
    get diagnostics n = row_count;
    counts := jsonb_set(counts, '{notifications.messages}', to_jsonb((counts->>'notifications.messages')::bigint + n));

    cut := platform.retention_cut(p_now, p_days, 'inbox');
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

-- ---------------------------------------------------------------------------------------- erasure

-- Erases pharmacy p_tenant, as in V3, with two changes. The cooling-off is checked against the
-- database's now(), whatever p_now says (a caller can no longer skip it with a date in the future);
-- p_now only stamps erased_at, and never later than now(). And the pharmacy's internal event
-- records go too: Spring Modulith's platform.event_publication (and its archive) has no tenant_uid,
-- the serialized event's metadata names the pharmacy ("tenantUid":"…"). An undelivered one would
-- otherwise be delivered again after a restart, for a pharmacy that no longer exists.
create or replace function platform.erase_tenant(p_tenant uuid, p_now timestamptz) returns jsonb
  language plpgsql
  security definer
  set search_path = pg_catalog, pg_temp
as $$
declare
  v_status   text;
  v_after    timestamptz;
  v_hold     boolean;
  counts     jsonb := '{}';
  remaining  text[];
  tbl        text;
  n          bigint;
  progress   boolean;
  v_event    text := '%"tenantUid":"' || p_tenant::text || '"%';
begin
  select status, erase_after, legal_hold into v_status, v_after, v_hold
    from tenancy.tenants where uid = p_tenant for update;
  if v_status is null then
    raise exception 'no tenant %', p_tenant using errcode = 'P0002';
  end if;
  if v_status <> 'CLOSED' then
    raise exception 'tenant % is % and must be closed first', p_tenant, v_status using errcode = 'P0001';
  end if;
  if v_hold then
    raise exception 'tenant % is under a legal hold', p_tenant using errcode = 'P0001';
  end if;
  if v_after is null or v_after > now() then
    raise exception 'tenant % is still in its cooling-off period', p_tenant using errcode = 'P0001';
  end if;

  -- Row-level security sees exactly this pharmacy; the append-only triggers let its rows go.
  perform set_config('pharmacy.tenant_uid', p_tenant::text, true);
  perform set_config('pharmacy.erasing_tenant', p_tenant::text, true);

  -- Rows without a tenant_uid of their own, hanging off the pharmacy's rows.
  delete from sales.sale_line_serials s
   using sales.sale_lines l
   where s.sale_line_id = l.id and l.tenant_uid = p_tenant;
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('sales.sale_line_serials', n);

  -- Its internal events, delivered or not (no row-level security on these tables).
  delete from platform.event_publication where serialized_event like v_event;
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('platform.event_publication', n);
  delete from platform.event_publication_archive where serialized_event like v_event;
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('platform.event_publication_archive', n);

  select array_agg(format('%I.%I', c.table_schema, c.table_name) order by c.table_schema, c.table_name)
    into remaining
    from information_schema.columns c
    join pg_namespace s on s.nspname = c.table_schema
    join pg_class k on k.relnamespace = s.oid and k.relname = c.table_name
   where c.column_name = 'tenant_uid'
     and k.relkind in ('r', 'p')
     and not k.relispartition
     and format('%I.%I', c.table_schema, c.table_name)
         not in ('tenancy.subscriptions', 'tenancy.subscription_invoices');

  -- Children before parents: a table whose rows are still referenced is tried again next pass.
  loop
    progress := false;
    foreach tbl in array coalesce(remaining, '{}') loop
      begin
        execute format('delete from %s where tenant_uid = $1', tbl) using p_tenant;
        get diagnostics n = row_count;
        counts := counts || jsonb_build_object(tbl, n);
        remaining := array_remove(remaining, tbl);
        progress := true;
      exception when foreign_key_violation then
        null;
      end;
    end loop;
    exit when remaining is null or cardinality(remaining) = 0;
    if not progress then
      raise exception 'cannot erase tenant %: % still referenced', p_tenant, remaining
        using errcode = 'P0001';
    end if;
  end loop;

  -- The pharmacy's own staff logins (personal accounts belong to their people and stay).
  create temporary table erased_logins on commit drop as
    select uid from access.users where managed_by_tenant_uid = p_tenant;
  update access.users set linked_to_uid = null where linked_to_uid in (select uid from erased_logins);
  delete from access.sessions where user_uid in (select uid from erased_logins);
  delete from access.password_resets where user_uid in (select uid from erased_logins);
  delete from access.phone_codes where user_uid in (select uid from erased_logins);
  delete from access.email_changes where user_uid in (select uid from erased_logins);
  delete from access.recovery_codes where user_uid in (select uid from erased_logins);
  delete from access.sign_in_challenges where user_uid in (select uid from erased_logins);
  delete from access.webauthn_ceremonies where user_uid in (select uid from erased_logins);
  delete from access.passkeys where user_uid in (select uid from erased_logins);
  delete from access.users where uid in (select uid from erased_logins);
  get diagnostics n = row_count;
  counts := counts || jsonb_build_object('access.users (staff logins)', n);

  update tenancy.tenants
     set status = 'ERASED', erased_at = least(p_now, now()), contact_name = null,
         contact_email = null, contact_phone = null, address = null
   where uid = p_tenant;

  perform set_config('pharmacy.erasing_tenant', '', true);
  return counts;
end
$$;

revoke all on function platform.erase_tenant(uuid, timestamptz) from public;
grant execute on function platform.erase_tenant(uuid, timestamptz) to ${application_role};
