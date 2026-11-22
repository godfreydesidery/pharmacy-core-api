-- Erasing a closed pharmacy's data, in one transaction, across every schema.
--
-- Append-only tables (the ledger, stock movements, allocations, remittances, the audit log) refuse
-- every change. The one exception: while platform.erase_tenant runs, it names the pharmacy being
-- erased (pharmacy.erasing_tenant) and only that pharmacy's rows may be deleted. The application
-- role still has no delete grant on those tables: only the function, which runs as their owner,
-- can delete.
create or replace function platform.refuse_change() returns trigger
  language plpgsql
as $$
begin
  if tg_op = 'DELETE'
     and nullif(current_setting('pharmacy.erasing_tenant', true), '') is not null
     and current_setting('pharmacy.erasing_tenant', true) = old.tenant_uid::text then
    return old;
  end if;
  raise exception '% is append-only', tg_table_name using errcode = 'P0001';
end
$$;

-- Erases pharmacy p_tenant: every row of every table that has a tenant_uid (except the platform's
-- billing records, tenancy.subscriptions and tenancy.subscription_invoices), the rows that hang off
-- them, and the pharmacy's own staff logins. It checks the rules itself, whatever calls it: the
-- pharmacy is CLOSED, its cooling-off ended before p_now, and it is not under a legal hold. The
-- tenancy.tenants row stays as a tombstone (ERASED), without its contact details. Returns the rows
-- deleted per table.
create function platform.erase_tenant(p_tenant uuid, p_now timestamptz) returns jsonb
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
  if v_after is null or v_after > p_now then
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
     set status = 'ERASED', erased_at = p_now, contact_name = null, contact_email = null,
         contact_phone = null, address = null
   where uid = p_tenant;

  perform set_config('pharmacy.erasing_tenant', '', true);
  return counts;
end
$$;

revoke all on function platform.erase_tenant(uuid, timestamptz) from public;
grant execute on function platform.erase_tenant(uuid, timestamptz) to ${application_role};
