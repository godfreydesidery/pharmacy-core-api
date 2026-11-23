-- The read-only role the logical backup (ops/backup/pg-dump-to-s3.sh) and the restore checks
-- (ops/backup/restore.sh) connect as. Run once per database server, as a superuser or the RDS
-- master user, then set its password from the secret store:
--
--   psql -v ON_ERROR_STOP=1 -f ops/backup/backup-role.sql
--   psql -c "alter role pharmacy_backup password '...'"      -- typed, never in a file or a log
--
-- Why BYPASSRLS: every tenant table has FORCED row-level security, so without it the role sees no
-- rows at all and pg_dump refuses ("query would be affected by row-level security policy").
-- It writes nothing: pg_read_all_data (PostgreSQL 14+) gives SELECT on every table and USAGE on
-- every schema, now and for tables added later, and nothing else. It owns nothing, cannot log in
-- as anything else and is not the application role, which must never bypass RLS.
--
-- On RDS the master user may grant BYPASSRLS only if it has it: check with
--   select rolbypassrls from pg_roles where rolname = current_user;
-- If it does not, run the backups as the master user itself (it is then the one secret to keep).

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'pharmacy_backup') then
    create role pharmacy_backup login nosuperuser nocreatedb nocreaterole inherit bypassrls;
  else
    alter role pharmacy_backup login nosuperuser nocreatedb nocreaterole inherit bypassrls;
  end if;
end
$$;

grant pg_read_all_data to pharmacy_backup;
-- A backup task never needs more than a few connections, and must not hang on for ever.
alter role pharmacy_backup connection limit 3;
alter role pharmacy_backup set statement_timeout = 0;
alter role pharmacy_backup set idle_in_transaction_session_timeout = '15min';
