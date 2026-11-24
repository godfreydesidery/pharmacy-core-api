-- The owner's decisions of 24 Nov 2026 (Batch 10 stream 5). The pharmacy's accountant confirms both
-- methods; each run records the rule it used, so a later change of the rule never re-posts a month.
--
-- 1. finance.expiry_provisions (STD-3): the monthly net-realisable-value write-down of stock near or
--    past expiry, one run per pharmacy and month. Stock stays at its FEFO batch cost; the provision
--    is a contra-asset, 1210 Inventory Expiry Provision, against 5110 Expiry Write-down. Each month
--    it is measured again and only the difference from the previous provision is posted (Dr 5110 /
--    Cr 1210 when it grows, the reverse when it shrinks), so 1210 always holds the latest measure
--    and the balance sheet shows inventory net of it. branches holds what was measured per branch
--    and band, rule the bands used.
-- 2. finance.vat_apportionments (STD-2 partial exemption): input VAT that serves both taxable and
--    exempt sales is split by the taxable share of sales (or a fixed percentage agreed with TRA);
--    the non-reclaimable part moves from 1150 VAT Receivable to 5150 Non-reclaimable Input VAT.
--    A MONTHLY run per month (provisional), an ANNUAL run in the year-end month (the true-up).
--
-- New tables only: no existing row is changed.
create table finance.expiry_provisions (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  tenant_uid         uuid not null,
  period             date not null check (extract(day from period) = 1),
  measured_on        date not null,
  enabled            boolean not null,
  rule               text not null,
  stock_at_cost      numeric(16,2) not null check (stock_at_cost >= 0),
  provision          numeric(16,2) not null check (provision >= 0),
  previous_provision numeric(16,2) not null check (previous_provision >= 0),
  adjustment         numeric(16,2) not null,
  journal_number     text,
  branches           jsonb not null default '[]'::jsonb,
  created_at         timestamptz not null,
  created_by         text not null,
  created_by_uid     uuid,
  updated_at         timestamptz,
  updated_by         text,
  updated_by_uid     uuid,
  version            bigint not null default 0,
  unique (tenant_uid, period),
  check (adjustment = provision - previous_provision)
);
alter table finance.expiry_provisions enable row level security;
alter table finance.expiry_provisions force row level security;
create policy tenant_isolation on finance.expiry_provisions
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on finance.expiry_provisions to ${application_role};

create table finance.vat_apportionments (
  id                 bigint generated always as identity primary key,
  uid                uuid not null unique,
  tenant_uid         uuid not null,
  period             date not null check (extract(day from period) = 1),
  kind               text not null check (kind in ('MONTHLY', 'ANNUAL')),
  method             text not null check (method in ('AUTOMATIC', 'FIXED')),
  ratio_percent      numeric(7,4) not null check (ratio_percent between 0 and 100),
  rounded_up         boolean not null,
  taxable_sales      numeric(16,2) not null,
  exempt_sales       numeric(16,2) not null,
  direct_taxable_vat numeric(16,2) not null check (direct_taxable_vat >= 0),
  direct_exempt_vat  numeric(16,2) not null check (direct_exempt_vat >= 0),
  residual_vat       numeric(16,2) not null check (residual_vat >= 0),
  non_reclaimable    numeric(16,2) not null check (non_reclaimable >= 0),
  adjustment         numeric(16,2) not null,
  journal_number     text,
  created_at         timestamptz not null,
  created_by         text not null,
  created_by_uid     uuid,
  updated_at         timestamptz,
  updated_by         text,
  updated_by_uid     uuid,
  version            bigint not null default 0,
  unique (tenant_uid, period, kind)
);
alter table finance.vat_apportionments enable row level security;
alter table finance.vat_apportionments force row level security;
create policy tenant_isolation on finance.vat_apportionments
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on finance.vat_apportionments to ${application_role};
