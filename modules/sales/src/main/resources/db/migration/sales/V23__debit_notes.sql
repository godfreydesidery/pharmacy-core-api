-- STD-2 (owner's decision of 24 Nov 2026; the accountant confirms the method): a sales debit note
-- charges a customer more after an invoice (an under-billing, a price corrected upwards). It is
-- numbered in its own series per branch (SDN-<branch>-000001), linked to the original sale, carries
-- VAT at the standard rate for a STANDARD supply (0 for ZERO_RATED and EXEMPT, and nothing while
-- the pharmacy is not VAT-registered), is charged to the sale's customer account (their statement
-- shows it) and is posted Dr 1100 / Cr 4000 (net) and Cr 2100 (VAT). With a fiscal provider on,
-- it gets a fiscal DEBIT_NOTE document (V24) like a return's credit note.
-- A new table only: no existing row is changed.
create table sales.debit_notes (
  id               bigint generated always as identity primary key,
  uid              uuid not null unique,
  tenant_uid       uuid not null,
  number           text not null,
  branch_uid       uuid not null,
  sale_uid         uuid not null,
  sale_number      text not null,
  customer_uid     uuid not null,
  reason           text not null check (length(reason) between 1 and 500),
  tax_category     text not null check (tax_category in ('STANDARD', 'ZERO_RATED', 'EXEMPT')),
  tax_rate_percent numeric(5,2) not null check (tax_rate_percent >= 0),
  net              numeric(14,2) not null check (net > 0),
  tax              numeric(14,2) not null check (tax >= 0),
  total            numeric(14,2) not null check (total = net + tax),
  fiscal_status    text check (fiscal_status in ('PENDING', 'ISSUED', 'FAILED')),
  fiscal_reference text,
  created_at       timestamptz not null,
  created_by       text not null,
  created_by_uid   uuid,
  updated_at       timestamptz,
  updated_by       text,
  updated_by_uid   uuid,
  version          bigint not null default 0,
  unique (tenant_uid, number)
);
create index debit_notes_by_sale on sales.debit_notes (tenant_uid, sale_uid);
create index debit_notes_by_time on sales.debit_notes (tenant_uid, created_at);
alter table sales.debit_notes enable row level security;
alter table sales.debit_notes force row level security;
create policy tenant_isolation on sales.debit_notes
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.debit_notes to ${application_role};
