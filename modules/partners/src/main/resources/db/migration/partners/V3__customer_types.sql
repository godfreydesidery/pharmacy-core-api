-- More customer types, each of which may have its own price list.

alter table partners.customers drop constraint if exists customers_type_check;
alter table partners.customers
  add constraint customers_type_check
  check (type in ('RETAIL', 'WHOLESALE', 'INSURANCE', 'CORPORATE', 'STAFF', 'NGO', 'GOVERNMENT'));
