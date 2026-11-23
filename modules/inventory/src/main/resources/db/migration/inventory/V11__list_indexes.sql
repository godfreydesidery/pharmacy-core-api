-- The uid-sorted lists (adversarial review PERF-6). A branch's stock ledger filtered by movement
-- type (say, its adjustments) read every movement of the branch and sorted them: the type now has
-- its own index. A branch's transfers in both directions (an OR of the two branch columns) walked
-- the global uid index and skipped every other pharmacy's transfers: (tenant_uid, uid) keeps the
-- walk inside the pharmacy. The other lists already had theirs (movements by branch, by branch and
-- product; transfers by sending and by receiving branch). Checked with EXPLAIN on a copy of the QA
-- database with synthetic volume.
--
-- Built CONCURRENTLY, so stock keeps moving while they build; Flyway runs this script outside a
-- transaction (V11__list_indexes.sql.conf), one statement at a time. If one fails, it leaves an
-- invalid index: drop that one (see the runbook "Migrations") and run the migration again; the ones
-- already built are skipped.
create index concurrently if not exists movements_by_tenant_branch_type_uid
  on inventory.movements (tenant_uid, branch_uid, type, uid);
create index concurrently if not exists transfers_by_tenant_uid
  on inventory.transfers (tenant_uid, uid);
