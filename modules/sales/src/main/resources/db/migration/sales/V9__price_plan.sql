-- The pricing plan a sale was priced under (null: Normal Sale), kept with its name as it was then.
-- normal_total: what the sale would have cost at Normal Sale prices, for the report of what each
-- plan gives away (or adds) against Normal Sale.
alter table sales.sales
  add column price_list_uid  uuid,
  add column price_list_name text,
  add column normal_total    numeric(14,2);
