-- The owner's controls of 23 Nov 2026 (adversarial review, Batch 4). Columns only, with defaults or
-- nullable: rows that exist take the default and nothing is updated.

-- The medicine's dispensing class when it was sold (FRD-6): a class changed later does not change
-- what the sale was. Null for lines sold before this; the return rule then reads the class now.
-- The promotion that priced the line, if any (FRD-8), kept with its name as it was then.
alter table sales.sale_lines
  add column schedule       text check (schedule in ('OTC', 'PRESCRIPTION', 'CONTROLLED')),
  add column promotion_uid  uuid,
  add column promotion_name text;

-- Where returned units went (FRD-4): SHELF back to sellable stock, DAMAGED written off at cost,
-- QUARANTINE held aside (held_quantity, not sellable) until released to the shelf or written off.
-- Returns before this went back to the shelf.
alter table sales.sale_return_lines
  add column disposition      text not null default 'SHELF'
                                check (disposition in ('SHELF', 'DAMAGED', 'QUARANTINE')),
  add column held_quantity    integer not null default 0 check (held_quantity >= 0),
  add column released_outcome text check (released_outcome in ('SHELF', 'DAMAGED')),
  add column released_at      timestamptz,
  add column released_by      text,
  add column released_by_uid  uuid;
create index sale_return_lines_held
  on sales.sale_return_lines (tenant_uid, return_id) where held_quantity > 0;

-- Where a sale's pricing plan came from (FRD-14): CUSTOMER, the customer's (or insurer's) own plan
-- other than Normal Sale, open to any seller; CHOSEN, another plan picked by someone holding
-- sales.price_plan. Null: Normal Sale, the customer's default.
alter table sales.sales
  add column price_plan_source text check (price_plan_source in ('CUSTOMER', 'CHOSEN'));
