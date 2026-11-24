-- Batch 10 (owner's decisions of 24 Nov 2026): two narrow permissions. Row-level security is forced
-- on both tables, so it is lifted for these backfills and put back (as V17 to V19 do).
alter table access.roles no force row level security;
alter table access.role_permissions no force row level security;

-- 1. "Export insurance claim batches" (insurance.claims_export). A claim batch export names the
-- patients and carries their diagnoses; until Batch 10 it needed finance.view only. The built-in
-- Administrator, Branch Manager and Accountant get it, as the presets do, and so does a pharmacy's
-- own role that holds finance.view and also either exports data (data.export) or keeps the books
-- (finance.manage, which gathers and submits the batches): the people who sent batches to payers
-- keep doing so. A role with finance.view alone (one that only reads the accounts) does not: the
-- diagnoses no longer go with a read-only finance role.
insert into access.role_permissions (role_id, permission)
select r.id, 'insurance.claims_export' from access.roles r
 where (r.system and r.code in ('ADMIN', 'BRANCH_MANAGER', 'ACCOUNTANT'))
    or (not r.system
        and exists (select 1 from access.role_permissions p
                     where p.role_id = r.id and p.permission = 'finance.view')
        and exists (select 1 from access.role_permissions p
                     where p.role_id = r.id and p.permission in ('data.export', 'finance.manage')))
on conflict do nothing;

-- 2. "Add a walk-in customer at the till" (partners.create_at_till): name, phone and email only.
-- A new ability for the built-in Cashier and Pharmacist (the Administrator and Branch Manager, who
-- manage customers anyway, hold it as their presets do). A pharmacy's own roles are left as they
-- are: their admin adds it in the role editor.
insert into access.role_permissions (role_id, permission)
select r.id, 'partners.create_at_till' from access.roles r
 where r.system and r.code in ('ADMIN', 'BRANCH_MANAGER', 'CASHIER', 'PHARMACIST')
on conflict do nothing;

alter table access.role_permissions force row level security;
alter table access.roles force row level security;
