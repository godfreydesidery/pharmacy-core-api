-- PERF-6 and PERF-15: the indexes the lists and the recall read by. Built CONCURRENTLY, so the
-- tables take writes while they build; Flyway runs this script outside a transaction
-- (V14__list_indexes.sql.conf). If it stops half way, see the runbook "Migrations": drop the
-- invalid index CONCURRENTLY and delete the failed history row, then migrate again.
--
-- Keyset lists are newest first by uid (UUIDv7) within the tenant (row-level security) and, for
-- the branch lists, the branch: where tenant_uid = ? and branch_uid = ? order by uid desc limit n.
create index concurrently if not exists sales_by_tenant_branch_uid
  on sales.sales (tenant_uid, branch_uid, uid);
-- The dashboard's latest sales of every branch: where tenant_uid = ? order by uid desc limit n.
create index concurrently if not exists sales_by_tenant_uid
  on sales.sales (tenant_uid, uid);
create index concurrently if not exists sale_returns_by_tenant_branch_uid
  on sales.sale_returns (tenant_uid, branch_uid, uid);
create index concurrently if not exists shifts_by_tenant_branch_uid
  on sales.shifts (tenant_uid, branch_uid, uid);
-- Vouchers are listed for the whole pharmacy.
create index concurrently if not exists vouchers_by_tenant_uid
  on sales.vouchers (tenant_uid, uid);
-- A recall: who bought this batch (SalesReports.batchSales).
create index concurrently if not exists sale_lines_by_tenant_batch_uid
  on sales.sale_lines (tenant_uid, batch_uid);
