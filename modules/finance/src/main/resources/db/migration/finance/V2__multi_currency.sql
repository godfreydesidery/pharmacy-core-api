-- Multi-currency. The ledger posts in the base currency only; a line may note the foreign currency
-- and amount behind it, for reference. Supplier payments settle payables and realise exchange
-- differences, posted to 7000 FX Gains/Losses (added to the default chart by the application).

alter table finance.journal_lines
  add column currency       text check (currency is null or currency ~ '^[A-Z]{3}$'),
  add column foreign_amount numeric(14,2);

create table finance.supplier_payments (
  id                  bigint generated always as identity primary key,
  uid                 uuid not null unique,
  tenant_uid          uuid not null,
  number              text not null,
  supplier_uid        uuid not null,
  supplier_name       text not null,
  amount              numeric(14,2) not null check (amount > 0),
  currency            text not null check (currency ~ '^[A-Z]{3}$'),
  exchange_rate       numeric(24,10) not null check (exchange_rate > 0),
  base_amount         numeric(14,2) not null,
  booked_base_amount  numeric(14,2) not null,
  fx_difference       numeric(14,2) not null,
  payment_method      text not null,
  reference           text,
  goods_receipt_uids  text,
  paid_on             date not null,
  branch_uid          uuid,
  created_at          timestamptz not null,
  created_by          text not null,
  updated_at          timestamptz,
  updated_by          text,
  version             bigint not null default 0,
  unique (tenant_uid, number)
);
create index supplier_payments_by_supplier on finance.supplier_payments (tenant_uid, supplier_uid);
alter table finance.supplier_payments enable row level security;
alter table finance.supplier_payments force row level security;
create policy tenant_isolation on finance.supplier_payments
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on finance.supplier_payments to ${application_role};
