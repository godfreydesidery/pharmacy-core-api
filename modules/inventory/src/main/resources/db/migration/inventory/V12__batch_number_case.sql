-- STD-18: batch numbers are compared without regard to case within a product: "ab123" received for
-- a product that holds batch "AB123" is that batch, not a second one. V13 enforces it with a unique
-- index on (tenant_uid, product_uid, lower(batch_number)).
--
-- Batches recorded before may already differ only in case. They are real stock with movements, so
-- nothing is merged or renamed: this migration REPORTS each such group (a WARNING in the migration
-- log) and marks every batch of a group but the first recorded as case_twin, which the index leaves
-- out. The first keeps receiving; the others are sold, counted and transferred as before. On the QA
-- copy of 24 Nov 2026 there were none.
alter table inventory.batches add column if not exists case_twin boolean not null default false;
comment on column inventory.batches.case_twin is
  'A batch recorded before batch numbers were compared without case whose number differs only in'
  ' case from an earlier batch of the product (V12). Kept as it is; left out of the unique index.';

-- The owner reads and marks every pharmacy's batches: lift the force of row-level security for the
-- statement and put it back.
alter table inventory.batches no force row level security;
do $$
declare
  r record;
  groups int := 0;
begin
  for r in
    select tenant_uid, product_uid,
           array_agg(id order by id) as ids,
           string_agg(batch_number, ', ' order by id) as numbers
      from inventory.batches
     group by tenant_uid, product_uid, lower(batch_number)
    having count(*) > 1
  loop
    groups := groups + 1;
    raise warning 'inventory: pharmacy % product % has batch numbers that differ only in case: % (the first is kept, the others marked case_twin)',
      r.tenant_uid, r.product_uid, r.numbers;
    update inventory.batches set case_twin = true where id = any (r.ids[2:]);
  end loop;
  raise notice 'inventory: % group(s) of batch numbers differing only in case', groups;
end
$$;
alter table inventory.batches force row level security;
