-- Multi-currency tenders: a payment may be in an accepted foreign currency. Its amount is in that
-- currency at the rate locked at the moment of sale (base units per 1 unit); base_amount is its
-- value in the tenant's base currency, which totals, coverage and change are computed in.
alter table sales.sale_payments
  add column currency      text,
  add column exchange_rate numeric(24, 10),
  add column base_amount   numeric(14, 2);

-- Existing payments are in their tenant's base currency. The owner runs this migration and the
-- table forces row-level security even for it, so the backfill lifts the force for its duration.
alter table sales.sale_payments no force row level security;
update sales.sale_payments p
   set currency = t.currency, exchange_rate = 1, base_amount = p.amount
  from tenancy.tenants t
 where t.uid = p.tenant_uid and p.currency is null;
alter table sales.sale_payments force row level security;

alter table sales.sale_payments
  alter column currency set not null,
  alter column exchange_rate set not null,
  alter column base_amount set not null,
  add constraint sale_payments_currency_iso check (currency ~ '^[A-Z]{3}$'),
  add constraint sale_payments_rate_positive check (exchange_rate > 0);
