-- STD-1 and STD-2 (adversarial review 23 Nov 2026): fiscal documents and VAT categories.
--
-- 1. sales.fiscal_documents: every document the tax authority (TRA VFD/EFD) issues for the
--    pharmacy, one row each:
--      kind      RECEIPT (a sale), CREDIT_NOTE (a return), Z_REPORT (a branch's day closed)
--      status    PENDING -> ISSUED, or FAILED when the authority refused it for good
--      the authority's receipt number, its verification code and QR payload (or URL), the
--      branch's global and daily counters (the VFD's GC and DC), when it was issued, how many
--      attempts were made and the last error; the lease of the attempt under way.
--    A RECEIPT or CREDIT_NOTE is written PENDING in the sale's (the return's) own transaction when a
--    fiscal integration is on, and issued after commit by the sale.completed (sale.returned)
--    reaction; the outbox delivers it again until it is issued. A Z_REPORT closes every issued
--    receipt and credit note of its branch that no Z report has closed yet (z_report_uid).
--    One document per (kind, source): the sale, the return, or the branch and day of a Z report.
-- 2. sales.fiscal_counters: the branch's counters, taken by one atomic upsert when a document is
--    first sent (global: ever; daily: reset each local business day).
-- 3. sales.sales.fiscal_status and sales.sale_returns.fiscal_status / fiscal_reference: the
--    document's status and number beside its sale or return, for lists (null: no fiscal document).
--    Written with SQL of their own after the document changes, never by an update of the row.
-- 4. sales.sale_lines.tax_category: the VAT category the line was sold under (STANDARD, ZERO_RATED
--    or EXEMPT). Null on lines sold before: their rate says it (above 0 STANDARD, else EXEMPT).
--
-- Every new column on an existing table is nullable without a default: nothing is rewritten and no
-- existing row is changed. sales.sales.fiscal_reference stays, filled from the receipt document.

alter table sales.sale_lines
  add column tax_category text
    check (tax_category is null or tax_category in ('STANDARD', 'ZERO_RATED', 'EXEMPT'));

alter table sales.sales
  add column fiscal_status text
    check (fiscal_status is null or fiscal_status in ('PENDING', 'ISSUED', 'FAILED'));

alter table sales.sale_returns
  add column fiscal_status text
    check (fiscal_status is null or fiscal_status in ('PENDING', 'ISSUED', 'FAILED')),
  add column fiscal_reference text;

create table sales.fiscal_documents (
  id                    bigint generated always as identity primary key,
  uid                   uuid not null unique,
  tenant_uid            uuid not null,
  branch_uid            uuid not null,
  kind                  text not null check (kind in ('RECEIPT', 'CREDIT_NOTE', 'Z_REPORT')),
  status                text not null check (status in ('PENDING', 'ISSUED', 'FAILED')),
  provider              text not null,
  source_uid            uuid not null,
  source_number         text not null,
  sale_uid              uuid,
  original_document_uid uuid,
  business_date         date not null,
  seller_tin            text,
  seller_vrn            text,
  total                 numeric(14,2) not null default 0,
  tax_total             numeric(14,2) not null default 0,
  standard_net          numeric(14,2) not null default 0,
  zero_rated_net        numeric(14,2) not null default 0,
  exempt_net            numeric(14,2) not null default 0,
  receipt_number        text,
  verification_code     text,
  qr_payload            text,
  global_counter        bigint,
  daily_counter         integer,
  issued_at             timestamptz,
  attempts              integer not null default 0,
  last_error            text,
  claimed_until         timestamptz,
  z_report_uid          uuid,
  z_receipt_count       integer,
  z_receipt_total       numeric(14,2),
  z_receipt_tax         numeric(14,2),
  z_credit_note_count   integer,
  z_credit_note_total   numeric(14,2),
  z_credit_note_tax     numeric(14,2),
  created_at            timestamptz not null,
  created_by            text not null,
  created_by_uid        uuid,
  updated_at            timestamptz,
  updated_by            text,
  updated_by_uid        uuid,
  version               bigint not null default 0,
  constraint fiscal_documents_one_per_source unique (tenant_uid, kind, source_uid)
);
create index fiscal_documents_by_sale on sales.fiscal_documents (tenant_uid, sale_uid);
create index fiscal_documents_pending
  on sales.fiscal_documents (tenant_uid, created_at) where status = 'PENDING';
create index fiscal_documents_to_close
  on sales.fiscal_documents (tenant_uid, branch_uid, business_date)
  where status = 'ISSUED' and z_report_uid is null and kind <> 'Z_REPORT';
create index fiscal_documents_z_reports
  on sales.fiscal_documents (tenant_uid, branch_uid, business_date desc, id desc)
  where kind = 'Z_REPORT';
alter table sales.fiscal_documents enable row level security;
alter table sales.fiscal_documents force row level security;
create policy tenant_isolation on sales.fiscal_documents
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.fiscal_documents to ${application_role};

create table sales.fiscal_counters (
  tenant_uid    uuid not null,
  branch_uid    uuid not null,
  global_value  bigint not null,
  day           date not null,
  daily_value   integer not null,
  primary key (tenant_uid, branch_uid)
);
alter table sales.fiscal_counters enable row level security;
alter table sales.fiscal_counters force row level security;
create policy tenant_isolation on sales.fiscal_counters
  using (tenant_uid = platform.current_tenant_uid())
  with check (tenant_uid = platform.current_tenant_uid());
grant select, insert, update on sales.fiscal_counters to ${application_role};
