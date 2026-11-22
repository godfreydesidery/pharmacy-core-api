-- Inactivity sign-out. active_at is when the person last used the app (a key, a click, a touch),
-- reported by the web app; requests alone do not count, as background ones (notifications,
-- refreshes) run with nobody at the screen. A session unused for idle_minutes ends. Null in either
-- column (sessions opened before this, or the check turned off) means no inactivity limit.

alter table access.sessions
  add column active_at timestamptz,
  add column idle_minutes integer check (idle_minutes is null or idle_minutes between 1 and 1440);
