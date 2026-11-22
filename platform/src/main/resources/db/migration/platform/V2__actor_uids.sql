-- Who did it, by account. created_by and updated_by keep the person's sign-in as it was at the
-- time (a snapshot that never changes); created_by_uid and updated_by_uid name the account, so a
-- record still belongs to the right person after their email changes. A platform service leaves
-- the uid null.
--
-- platform.add_actor_uids(schema) adds both columns to every table of the schema that records who
-- created it, and fills them for rows written before, by matching the email in access.users. Each
-- module calls it once from its own migration. Migrations run as the tables' owner: while filling,
-- row-level security is not forced on the owner and append-only triggers are paused, then both are
-- restored. A table added later declares the columns itself (see TenantEntity).
create function platform.add_actor_uids(target_schema text) returns void
  language plpgsql
as $$
declare
  t record;
  users_exist boolean := to_regclass('access.users') is not null;
begin
  for t in
    select c.relname as name,
           c.relforcerowsecurity as forced,
           exists (select 1 from information_schema.columns u
                   where u.table_schema = target_schema and u.table_name = c.relname
                     and u.column_name = 'updated_by') as has_updated_by
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = target_schema
      and c.relkind in ('r', 'p')
      and not c.relispartition
      and exists (select 1 from information_schema.columns i
                  where i.table_schema = target_schema and i.table_name = c.relname
                    and i.column_name = 'created_by')
  loop
    execute format('alter table %I.%I add column if not exists created_by_uid uuid',
                   target_schema, t.name);
    if t.has_updated_by then
      execute format('alter table %I.%I add column if not exists updated_by_uid uuid',
                     target_schema, t.name);
    end if;
    if users_exist then
      if t.forced then
        execute format('alter table %I.%I no force row level security', target_schema, t.name);
      end if;
      execute format('alter table %I.%I disable trigger user', target_schema, t.name);
      execute format(
          'update %I.%I x set created_by_uid = u.uid from access.users u'
          || ' where x.created_by_uid is null and u.email = x.created_by',
          target_schema, t.name);
      if t.has_updated_by then
        execute format(
            'update %I.%I x set updated_by_uid = u.uid from access.users u'
            || ' where x.updated_by_uid is null and u.email = x.updated_by',
            target_schema, t.name);
      end if;
      execute format('alter table %I.%I enable trigger user', target_schema, t.name);
      if t.forced then
        execute format('alter table %I.%I force row level security', target_schema, t.name);
      end if;
    end if;
  end loop;
end
$$;
