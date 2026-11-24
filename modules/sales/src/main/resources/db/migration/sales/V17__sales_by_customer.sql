-- STD-5: one person's sales, read for their data export and rewritten by their anonymisation:
-- where tenant_uid = ? and customer_uid = ?. Built CONCURRENTLY, so the table takes writes while it
-- builds; Flyway runs this script outside a transaction (V17__sales_by_customer.sql.conf). If it
-- stops half way, see the runbook "Migrations": drop the invalid index CONCURRENTLY and delete the
-- failed history row, then migrate again.
create index concurrently if not exists sales_by_tenant_customer
  on sales.sales (tenant_uid, customer_uid, uid) where customer_uid is not null;
