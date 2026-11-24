-- STD-1/STD-2: a sales debit note gets a fiscal document of its own kind, DEBIT_NOTE, issued through
-- the fiscal port like a return's credit note. The kind check is replaced; existing rows are
-- RECEIPT, CREDIT_NOTE or Z_REPORT and keep passing (the constraint is added NOT VALID and then
-- validated, which only reads the table and does not block writes for long).
alter table sales.fiscal_documents drop constraint if exists fiscal_documents_kind_check;
alter table sales.fiscal_documents
  add constraint fiscal_documents_kind_check
  check (kind in ('RECEIPT', 'CREDIT_NOTE', 'DEBIT_NOTE', 'Z_REPORT')) not valid;
alter table sales.fiscal_documents validate constraint fiscal_documents_kind_check;
