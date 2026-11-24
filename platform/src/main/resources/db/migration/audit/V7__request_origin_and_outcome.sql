-- STD-22 / FRD-19 (ISO 27001 A.8.15): an audit entry says where it came from and how it ended.
--   ip           the client's IP address as the edge reads it behind its proxies (ClientAddress)
--   user_agent   the client's user agent, at most 300 characters
--   session_uid  the sign-in session the request ran in (none for a job or a reaction)
--   outcome      SUCCESS, or FAILURE for an attempt that was refused or failed (a failed sign-in,
--                a root request answered with an error)
-- Nullable and without a default: adding them changes only the catalogue, on the parent and every
-- monthly partition, and the append-only entries before them keep nulls.
alter table audit.entries
  add column ip text,
  add column user_agent text,
  add column session_uid uuid,
  add column outcome text check (outcome in ('SUCCESS', 'FAILURE'));
