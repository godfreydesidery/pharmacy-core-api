-- The customer list of a pharmacy, newest first (uid order), read one page at a time (adversarial
-- review PERF-6): without it, each page read and sorted all of the pharmacy's customers.
-- CONCURRENTLY, so the table takes writes while it builds; it cannot run in a transaction, so it is
-- alone in its migration and V6__customers_by_uid.sql.conf says executeInTransaction=false. The
-- drop first makes a run that failed half-way (leaving an invalid index) safe to run again.
drop index concurrently if exists partners.customers_by_tenant_uid;
create index concurrently customers_by_tenant_uid on partners.customers (tenant_uid, uid);
