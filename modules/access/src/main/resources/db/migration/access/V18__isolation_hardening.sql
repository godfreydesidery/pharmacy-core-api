-- Isolation between pharmacies and staff (adversarial review ISO-7, ISO-10, ISO-11).

-- 1. "Export people's data in bulk" (data.export), ISO-10. The customer list with phones and
-- insurance numbers, and the controlled-drugs register with its patients and prescriptions, leave
-- the system only with it: personal data under Tanzania's Personal Data Protection Act 2022. Until
-- now partners.view (customers) and inventory.view (the register) were enough, and cashiers and
-- pharmacists hold both. The owner's decision of 23 Nov 2026: in every existing pharmacy the
-- built-in Administrator, Branch Manager and Accountant get it, as the presets do for new
-- pharmacies, and so does any role of the pharmacy's own that manages its users or its settings
-- (the people who run it). Cashiers, pharmacists and store keepers lose the bulk export (they keep
-- the lists on screen). Row-level security is forced on both tables, so it is lifted for this
-- backfill and put back.
alter table access.roles no force row level security;
alter table access.role_permissions no force row level security;

insert into access.role_permissions (role_id, permission)
select r.id, 'data.export' from access.roles r
 where (r.system and r.code in ('ADMIN', 'BRANCH_MANAGER', 'ACCOUNTANT'))
    or (not r.system and exists (select 1 from access.role_permissions p
                                  where p.role_id = r.id
                                    and p.permission in ('users.manage', 'settings.manage')))
on conflict do nothing;

alter table access.role_permissions force row level security;
alter table access.roles force row level security;

-- 2. Reading and writing are told apart, ISO-7. A person may READ their own memberships and roles
-- in other pharmacies (to sign in and choose one) and the role named on an invitation they hold;
-- every WRITE, a role's permissions and a membership's branches included, is the current
-- pharmacy's only. Before, a child row's check was only "its parent is visible", so a person's own
-- role or membership elsewhere could have its permissions or branches changed from this pharmacy,
-- and an UPDATE could move a visible row into this pharmacy.
drop policy tenant_or_own on access.roles;
create policy tenant_or_own on access.roles for select
  using (
    tenant_uid = platform.current_tenant_uid()
    or exists (
      select 1 from access.memberships m
      where m.role_id = roles.id and m.user_uid = platform.current_user_uid())
    or exists (
      select 1 from access.invitations i
      where i.role_id = roles.id
        and i.token_hash = nullif(current_setting('pharmacy.invitation_token_hash', true), '')));
create policy this_pharmacy_adds on access.roles for insert
  with check (tenant_uid = platform.current_tenant_uid());
create policy this_pharmacy_changes on access.roles for update
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
-- The application role may not delete roles; the pharmacy's erasure (as the owner, named
-- pharmacy) does.
create policy this_pharmacy_removes on access.roles for delete
  using (tenant_uid = platform.current_tenant_uid());

drop policy tenant_or_own on access.memberships;
create policy tenant_or_own on access.memberships for select
  using (tenant_uid = platform.current_tenant_uid() or user_uid = platform.current_user_uid());
create policy this_pharmacy_adds on access.memberships for insert
  with check (tenant_uid = platform.current_tenant_uid());
create policy this_pharmacy_changes on access.memberships for update
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
create policy this_pharmacy_removes on access.memberships for delete
  using (tenant_uid = platform.current_tenant_uid());

drop policy follows_role on access.role_permissions;
create policy follows_role on access.role_permissions for select
  using (exists (select 1 from access.roles r where r.id = role_id));
create policy role_of_this_pharmacy on access.role_permissions for insert
  with check (exists (select 1 from access.roles r
                       where r.id = role_id and r.tenant_uid = platform.current_tenant_uid()));
create policy role_of_this_pharmacy_goes on access.role_permissions for delete
  using (exists (select 1 from access.roles r
                  where r.id = role_id and r.tenant_uid = platform.current_tenant_uid()));

drop policy follows_membership on access.membership_branches;
create policy follows_membership on access.membership_branches for select
  using (exists (select 1 from access.memberships m where m.id = membership_id));
create policy membership_of_this_pharmacy on access.membership_branches for insert
  with check (exists (select 1 from access.memberships m
                       where m.id = membership_id and m.tenant_uid = platform.current_tenant_uid()));
create policy membership_of_this_pharmacy_goes on access.membership_branches for delete
  using (exists (select 1 from access.memberships m
                  where m.id = membership_id and m.tenant_uid = platform.current_tenant_uid()));

-- 3. The pharmacies a session entered, ISO-11. A session works in no pharmacy, or in several, and
-- is recorded and read before any tenant is set (the edge notes it while resolving the request;
-- sign-out reads it with no tenant), so a tenant policy cannot fit it. Instead the application no
-- longer reads or writes the table at all: two SECURITY DEFINER functions, owned by the schema
-- owner with a fixed search path, record a live session's entry and list one session's
-- pharmacies. No query of the application can list who works where, or join one pharmacy's staff
-- to their sessions elsewhere. The nightly retention and the erasure run as the owner and are
-- unchanged.
create function access.enter_pharmacy(p_session uuid, p_tenant uuid, p_at timestamptz)
  returns integer
  language plpgsql
  security definer
  set search_path = pg_catalog, pg_temp
as $$
declare
  n integer;
begin
  insert into access.session_pharmacies (session_uid, tenant_uid, entered_at)
  select p_session, p_tenant, p_at
   where p_tenant is not null
     and exists (select 1 from access.sessions s
                  where s.uid = p_session and s.revoked_at is null and s.expires_at > p_at)
  on conflict do nothing;
  get diagnostics n = row_count;
  return n;
end
$$;

create function access.entered_pharmacies(p_session_id bigint)
  returns setof uuid
  language sql
  stable
  security definer
  set search_path = pg_catalog, pg_temp
as $$
  select sp.tenant_uid
    from access.session_pharmacies sp
    join access.sessions s on s.uid = sp.session_uid
   where s.id = p_session_id
$$;

revoke all on function access.enter_pharmacy(uuid, uuid, timestamptz) from public;
revoke all on function access.entered_pharmacies(bigint) from public;
grant execute on function access.enter_pharmacy(uuid, uuid, timestamptz) to ${application_role};
grant execute on function access.entered_pharmacies(bigint) to ${application_role};
revoke all on access.session_pharmacies from ${application_role};
