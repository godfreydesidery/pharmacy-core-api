-- STD-2 (owner's decision of 24 Nov 2026; the accountant confirms the method): a supplier's debit
-- note raises what the pharmacy owes for a goods receipt (the supplier under-billed). It is
-- numbered in its own series per branch (PDN-<branch>-000001), linked to the receipt, kept in the
-- base currency (a receipt in a foreign currency is refused), and posted Dr 5120 Purchase Price
-- Differences (net) and Dr 1150 VAT receivable (its input VAT, for a VAT-registered pharmacy) /
-- Cr 2000 accounts payable (total). The stock's cost is not changed: the goods may be sold already.
-- The supplier's statement shows it as DEBIT_NOTE. Append-only, like receipts and returns.
-- A new table only: no existing row is changed.
create table purchasing.supplier_debit_notes (
  id                   bigint generated always as identity primary key,
  uid                  uuid not null unique,
  tenant_uid           uuid not null,
  number               text not null,
  branch_uid           uuid not null,
  goods_receipt_uid    uuid not null,
  goods_receipt_number text not null,
  supplier_uid         uuid not null,
  supplier_name        text not null,
  supplier_reference   text,
  reason               text not null check (length(reason) between 1 and 500),
  net                  numeric(14,2) not null check (net > 0),
  vat                  numeric(14,2) not null default 0 check (vat >= 0),
  total                numeric(14,2) not null check (total = net + vat),
  created_at           timestamptz not null,
  created_by           text not null,
  created_by_uid       uuid,
  updated_at           timestamptz,
  updated_by           text,
  updated_by_uid       uuid,
  version              bigint not null default 0,
  unique (tenant_uid, number)
);
create index supplier_debit_notes_by_receipt
  on purchasing.supplier_debit_notes (tenant_uid, goods_receipt_uid);
create index supplier_debit_notes_by_supplier
  on purchasing.supplier_debit_notes (tenant_uid, supplier_uid);
alter table purchasing.supplier_debit_notes enable row level security;
alter table purchasing.supplier_debit_notes force row level security;
create policy tenant_isolation on purchasing.supplier_debit_notes
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert on purchasing.supplier_debit_notes to ${application_role};
