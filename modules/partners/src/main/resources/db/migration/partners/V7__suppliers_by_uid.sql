-- The supplier list of a pharmacy, newest first (uid order), read one page at a time (adversarial
-- review PERF-6). CONCURRENTLY and outside a transaction, as V6.
drop index concurrently if exists partners.suppliers_by_tenant_uid;
create index concurrently suppliers_by_tenant_uid on partners.suppliers (tenant_uid, uid);
