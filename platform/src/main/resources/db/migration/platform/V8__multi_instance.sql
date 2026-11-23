-- Several instances of the API side by side (adversarial review of 23 Nov 2026: OPS-7, OPS-8,
-- INT-12, AUTH-14; owner's answer 7).
--
-- 1. platform.job_runs: every run of a scheduled job, on whichever instance ran it (or skipped it
--    because another instance held the job's advisory lock), with its outcome and the pharmacies it
--    failed for. GET /api/v1/platform/jobs reads it; the nightly retention keeps 30 days.
-- 2. platform.rate_windows: the sign-in limits and request throttles shared by every instance
--    (failures per client and per unknown email, sign-in requests per client a minute, root
--    activity notes). One row per key holds the key's recent hits, a sliding-window log, so the
--    limits behave exactly as the in-memory windows did. Keys are hashed by the application (no
--    email or IP address is stored); the nightly retention deletes expired rows.
--
-- New tables only: no existing row is changed by this migration.

-- ---------------------------------------------------------------------------------------- job runs

create table platform.job_runs (
  id          bigint generated always as identity primary key,
  job         text not null check (length(job) between 1 and 100),
  instance    text not null,
  started_at  timestamptz not null default now(),
  finished_at timestamptz,
  -- null while the run is in progress (or when its instance died during it)
  outcome     text check (outcome in ('OK', 'FAILED', 'SKIPPED')),
  message     text,
  -- [{"tenantUid": "...", "error": "..."}], one entry per pharmacy (or part) the run failed for
  failures    jsonb not null default '[]'::jsonb
);
create index job_runs_by_job on platform.job_runs (job, started_at desc);
create index job_runs_by_start on platform.job_runs (started_at);
-- Platform operations data: no pharmacy owns a row, so no row-level security. Only root reads it
-- (through the API); the application inserts, finishes and trims runs.
grant select, insert, update, delete on platform.job_runs to ${application_role};

-- ------------------------------------------------------------------------------------ rate windows

create table platform.rate_windows (
  -- "<kind>:<sha-256 of the key, hex>", e.g. "signin-ip:9f86d0…"
  key          text primary key check (length(key) between 1 and 200),
  -- the hits (requests or failures) still inside the window, oldest first
  hits         timestamptz[] not null default '{}',
  -- a failure window that tripped is locked until then
  locked_until timestamptz,
  -- nothing left to remember after this: the nightly retention deletes the row
  expires_at   timestamptz not null
);
create index rate_windows_by_expiry on platform.rate_windows (expires_at);
grant select, insert, update, delete on platform.rate_windows to ${application_role};

-- One request of p_key at p_at, allowed when fewer than p_limit hits fell in the last p_window
-- (a sliding-window log, exact at the window's edge). Returns 0 when allowed, otherwise the whole
-- seconds (at least 1) until the oldest hit leaves the window; a refused request is not counted.
-- The upsert locks the key's row, so concurrent requests on every instance are counted one by one.
create function platform.rate_acquire(p_key text, p_limit int, p_window interval, p_at timestamptz)
  returns int
  language plpgsql
  set search_path = pg_catalog, pg_temp
as $$
declare
  seen    timestamptz[];
  kept    timestamptz[];
begin
  insert into platform.rate_windows as w (key, hits, expires_at)
  values (p_key, '{}', p_at + p_window)
  on conflict (key) do update set key = w.key
  returning w.hits into seen;

  select coalesce(array_agg(h order by h), '{}') into kept
    from unnest(seen) h where h > p_at - p_window;

  if cardinality(kept) >= greatest(p_limit, 0) then
    update platform.rate_windows set hits = kept where key = p_key;
    if cardinality(kept) = 0 then
      return greatest(1, ceil(extract(epoch from p_window))::int);
    end if;
    return greatest(1, ceil(extract(epoch from (kept[1] + p_window - p_at)))::int);
  end if;

  update platform.rate_windows
     set hits = kept || p_at,
         expires_at = greatest(expires_at, p_at + p_window)
   where key = p_key;
  return 0;
end
$$;

-- One failure of p_key at p_at: p_max failures within p_window lock the key until p_at + p_lock
-- (the count then starts again). Returns whether this failure locked it.
create function platform.rate_fail(
    p_key text, p_max int, p_window interval, p_lock interval, p_at timestamptz)
  returns boolean
  language plpgsql
  set search_path = pg_catalog, pg_temp
as $$
declare
  seen    timestamptz[];
  kept    timestamptz[];
begin
  insert into platform.rate_windows as w (key, hits, expires_at)
  values (p_key, '{}', p_at + p_window)
  on conflict (key) do update set key = w.key
  returning w.hits into seen;

  select coalesce(array_agg(h order by h), '{}') into kept
    from unnest(seen) h where h > p_at - p_window;
  kept := kept || p_at;

  if cardinality(kept) >= p_max then
    update platform.rate_windows
       set hits = '{}',
           locked_until = p_at + p_lock,
           expires_at = greatest(expires_at, p_at + p_lock, p_at + p_window)
     where key = p_key;
    return true;
  end if;

  update platform.rate_windows
     set hits = kept,
         expires_at = greatest(expires_at, p_at + p_window)
   where key = p_key;
  return false;
end
$$;

revoke all on function platform.rate_acquire(text, int, interval, timestamptz) from public;
revoke all on function platform.rate_fail(text, int, interval, interval, timestamptz) from public;
grant execute on function platform.rate_acquire(text, int, interval, timestamptz) to ${application_role};
grant execute on function platform.rate_fail(text, int, interval, interval, timestamptz) to ${application_role};
