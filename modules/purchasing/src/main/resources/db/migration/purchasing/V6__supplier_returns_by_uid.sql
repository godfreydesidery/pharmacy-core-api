-- A branch's supplier returns, newest first (uid order), read one page at a time (adversarial
-- review PERF-6); the index of V4_7 is in id order, which the list does not use. Purchase orders
-- and goods receipts have theirs since V1 ((tenant_uid, branch_uid, uid)). CONCURRENTLY, so the
-- table takes writes while it builds; it cannot run in a transaction, so it is alone in its
-- migration and V6__supplier_returns_by_uid.sql.conf says executeInTransaction=false. The drop
-- first makes a run that failed half-way (leaving an invalid index) safe to run again.
drop index concurrently if exists purchasing.supplier_returns_by_tenant_branch_uid;
create index concurrently supplier_returns_by_tenant_branch_uid
  on purchasing.supplier_returns (tenant_uid, branch_uid, uid);
