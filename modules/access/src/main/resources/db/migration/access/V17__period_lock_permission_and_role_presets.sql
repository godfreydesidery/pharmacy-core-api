-- "Lock and unlock the books' period" (finance.period_lock) is its own permission: until now
-- finance.manage moved the lock (adversarial review FRD-10). Nobody loses the ability today (the
-- owner's decision of 23 Nov 2026): every role that holds finance.manage in an existing pharmacy -
-- built-in or the pharmacy's own - gets the new permission, and the built-in Administrator and
-- Accountant get it in any case. New pharmacies get it from the presets (Administrator and
-- Accountant only); an existing pharmacy's admin can take it off the Branch Manager by applying the
-- recommended roles. Row-level security is forced on both tables, so it is lifted for this backfill
-- and put back.
alter table access.roles no force row level security;
alter table access.role_permissions no force row level security;

insert into access.role_permissions (role_id, permission)
select r.id, 'finance.period_lock' from access.roles r
 where (r.system and r.code in ('ADMIN', 'ACCOUNTANT'))
    or exists (select 1 from access.role_permissions p
                where p.role_id = r.id and p.permission = 'finance.manage')
on conflict do nothing;

-- "Set and change customers' credit limits" (partners.credit_limit) is its own permission: the
-- person who sells on credit should not also decide how much credit there is (FRD-17). Until now
-- partners.manage set limits, so every role that holds partners.manage or finance.manage today -
-- built-in or the pharmacy's own - keeps the ability. New pharmacies get it from the presets
-- (Administrator, Branch Manager, Accountant); an existing pharmacy's admin can take it off the
-- Pharmacist by applying the recommended roles.
insert into access.role_permissions (role_id, permission)
select r.id, 'partners.credit_limit' from access.roles r
 where (r.system and r.code = 'ADMIN')
    or exists (select 1 from access.role_permissions p
                where p.role_id = r.id and p.permission in ('partners.manage', 'finance.manage'))
on conflict do nothing;

alter table access.role_permissions force row level security;
alter table access.roles force row level security;

-- The one-time notice "Apply recommended roles": a pharmacy's admin decides once per version of the
-- role presets, either applying them to the built-in roles or keeping the pharmacy's own.
create table access.role_preset_decisions (
  id              bigint generated always as identity primary key,
  uid             uuid not null unique,
  tenant_uid      uuid not null,
  presets_version text not null,
  decision        text not null check (decision in ('APPLIED', 'DISMISSED')),
  created_at      timestamptz not null,
  created_by      text not null,
  created_by_uid  uuid,
  updated_at      timestamptz,
  updated_by      text,
  updated_by_uid  uuid,
  version         bigint not null default 0,
  unique (tenant_uid, presets_version)
);
grant select, insert, update on access.role_preset_decisions to ${application_role};
alter table access.role_preset_decisions enable row level security;
alter table access.role_preset_decisions force row level security;
create policy tenant_isolation on access.role_preset_decisions
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
