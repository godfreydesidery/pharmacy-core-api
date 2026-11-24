-- Batch 9 (STD-5, STD-7, STD-8): three new permissions. Row-level security is forced on both
-- tables, so it is lifted for these backfills and put back (as V17 and V18 do).
alter table access.roles no force row level security;
alter table access.role_permissions no force row level security;

-- 1. "Dispense prescription medicines" (sales.dispense_rx), STD-7. A sale with a PRESCRIPTION or
-- CONTROLLED line now needs it (403 dispense_not_allowed). Nobody loses today's ability: every
-- role that can sell (sales.sell), built-in or the pharmacy's own, gets it, the built-in Cashier
-- included; the built-in Administrator, Branch Manager and Pharmacist get it in any case, as the
-- presets do. A pharmacy's admin takes it off the Cashier by applying the recommended roles, or in
-- the role editor.
insert into access.role_permissions (role_id, permission)
select r.id, 'sales.dispense_rx' from access.roles r
 where (r.system and r.code in ('ADMIN', 'BRANCH_MANAGER', 'PHARMACIST'))
    or exists (select 1 from access.role_permissions p
                where p.role_id = r.id and p.permission = 'sales.sell')
on conflict do nothing;

-- 2. "View health details" (health.view), STD-5: a sale's prescription, the register's patients,
-- diagnosis codes, adverse-reaction reports. The built-in Administrator, Branch Manager and
-- Pharmacist get it, as the presets do, and so does any role of the pharmacy's own that records
-- returns or manages the catalogue (sales.refund, catalogue.manage): the people who handle
-- dispensing records today keep seeing them. Cashiers, store keepers and accountants no longer
-- read patients and prescribers on screen.
insert into access.role_permissions (role_id, permission)
select r.id, 'health.view' from access.roles r
 where (r.system and r.code in ('ADMIN', 'BRANCH_MANAGER', 'PHARMACIST'))
    or (not r.system and exists (select 1 from access.role_permissions p
                                  where p.role_id = r.id
                                    and p.permission in ('sales.refund', 'catalogue.manage')))
on conflict do nothing;

-- 3. "Report adverse drug reactions" (adr.report), STD-8: a new ability, given to the same roles as
-- health.view, so whoever reads the reports can also file one.
insert into access.role_permissions (role_id, permission)
select r.id, 'adr.report' from access.roles r
 where (r.system and r.code in ('ADMIN', 'BRANCH_MANAGER', 'PHARMACIST'))
    or (not r.system and exists (select 1 from access.role_permissions p
                                  where p.role_id = r.id
                                    and p.permission in ('sales.refund', 'catalogue.manage')))
on conflict do nothing;

alter table access.role_permissions force row level security;
alter table access.roles force row level security;
