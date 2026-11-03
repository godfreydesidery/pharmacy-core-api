-- Multi-currency: a purchase order or goods receipt is priced in a currency at a rate locked when
-- it is made (base units per 1 document unit); base_total is its total in the base currency.
alter table purchasing.purchase_orders
  add column currency      text,
  add column exchange_rate numeric(24, 10),
  add column base_total    numeric(14, 2);
alter table purchasing.goods_receipts
  add column currency      text,
  add column exchange_rate numeric(24, 10),
  add column base_total    numeric(14, 2);

-- Existing documents are in their tenant's base currency. The owner runs this migration, and the
-- tables force row-level security even for it, so the backfill lifts the force for its duration.
alter table purchasing.purchase_orders no force row level security;
alter table purchasing.goods_receipts no force row level security;
update purchasing.purchase_orders d
   set currency = t.currency, exchange_rate = 1, base_total = d.total
  from tenancy.tenants t
 where t.uid = d.tenant_uid and d.currency is null;
update purchasing.goods_receipts d
   set currency = t.currency, exchange_rate = 1, base_total = d.total
  from tenancy.tenants t
 where t.uid = d.tenant_uid and d.currency is null;
alter table purchasing.purchase_orders force row level security;
alter table purchasing.goods_receipts force row level security;

alter table purchasing.purchase_orders
  alter column currency set not null,
  alter column exchange_rate set not null,
  alter column base_total set not null,
  add constraint purchase_orders_currency_iso check (currency ~ '^[A-Z]{3}$'),
  add constraint purchase_orders_rate_positive check (exchange_rate > 0);
alter table purchasing.goods_receipts
  alter column currency set not null,
  alter column exchange_rate set not null,
  alter column base_total set not null,
  add constraint goods_receipts_currency_iso check (currency ~ '^[A-Z]{3}$'),
  add constraint goods_receipts_rate_positive check (exchange_rate > 0);
