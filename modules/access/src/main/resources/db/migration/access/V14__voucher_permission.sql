-- "Issue vouchers" is its own permission: a voucher is money, so selling at the till no longer
-- lets anyone create one. The owner's and branch managers' built-in roles get it in every existing
-- pharmacy (new pharmacies get it from the role presets). Row-level security is forced on both
-- tables, so it is lifted for this backfill and put back.
alter table access.roles no force row level security;
alter table access.role_permissions no force row level security;

insert into access.role_permissions (role_id, permission)
select r.id, 'sales.voucher' from access.roles r
 where r.system and r.code in ('ADMIN', 'BRANCH_MANAGER')
on conflict do nothing;

alter table access.role_permissions force row level security;
alter table access.roles force row level security;
