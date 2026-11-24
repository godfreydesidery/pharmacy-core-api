-- STD-18: one batch number per product whatever its case (V12 marked the older twins). Built
-- CONCURRENTLY, so stock keeps moving while it builds; Flyway runs this script outside a
-- transaction (V13__batch_number_case_index.sql.conf), one statement at a time.
--
-- An instance still on the previous version may record a twin between V12 and this index (a batch
-- number typed in another case in those seconds): it is marked here first. Should the build fail
-- anyway, it leaves an invalid index; repair the Flyway history and run it again: the first
-- statement drops that index.
drop index concurrently if exists inventory.batches_unique_number_any_case;
alter table inventory.batches no force row level security;
update inventory.batches b
   set case_twin = true
 where not b.case_twin
   and exists (select 1
                 from inventory.batches o
                where o.tenant_uid = b.tenant_uid
                  and o.product_uid = b.product_uid
                  and lower(o.batch_number) = lower(b.batch_number)
                  and not o.case_twin
                  and o.id < b.id);
alter table inventory.batches force row level security;
create unique index concurrently batches_unique_number_any_case
  on inventory.batches (tenant_uid, product_uid, lower(batch_number))
  where not case_twin;
