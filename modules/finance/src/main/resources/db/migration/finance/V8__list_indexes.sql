-- The uid-sorted lists (adversarial review PERF-6): the journal and the expenses page newest uid
-- first within the pharmacy (and, for expenses, within the branches the caller may see). Without
-- these, PostgreSQL walks the global uid index backwards and skips every other pharmacy's rows. The
-- journal's date filter keeps its (tenant_uid, entry_date) index. Checked with EXPLAIN on a copy of
-- the QA database with synthetic volume.
--
-- Built CONCURRENTLY, so postings and expenses keep being written while they build; Flyway runs
-- this script outside a transaction (V8__list_indexes.sql.conf), one statement at a time. If one
-- fails, it leaves an invalid index: drop that one (see the runbook "Migrations") and run the
-- migration again; the ones already built are skipped.
create index concurrently if not exists journal_entries_by_tenant_uid
  on finance.journal_entries (tenant_uid, uid);
create index concurrently if not exists expenses_by_tenant_uid
  on finance.expenses (tenant_uid, uid);
create index concurrently if not exists expenses_by_tenant_branch_uid
  on finance.expenses (tenant_uid, branch_uid, uid);
